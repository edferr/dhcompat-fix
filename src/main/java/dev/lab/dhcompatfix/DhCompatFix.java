package dev.lab.dhcompatfix;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Repairs a stale Iris/Distant Horizons compat instance on the active pipeline.
 *
 * <h2>The defect</h2>
 * Confirmed from {@code DHCompatInternal.<init>} bytecode:
 *
 * <pre>
 *   15: ifnull 45                              // pipeline == null            -> return
 *   18: DhApi$Delayed.configs.graphics()
 *       .renderingEnabled().getValue()         // Boolean
 *   42: ifne   46                              // TRUE  -> build programs and framebuffers
 *   45: return                                 // FALSE -> bail, incompatible stays false
 * </pre>
 *
 * <p>When a pipeline is built while DH reports rendering disabled, the constructor
 * returns at offset 45 leaving {@code solidProgram}, {@code dhTerrainFramebuffer} and
 * {@code genericShader} all null and {@code incompatible == false}. That empty object is
 * cached in {@code DHCompat.compatInternalInstance} for the life of the pipeline. Iris
 * therefore believes DH is wired up while drawing nothing: LODs silently vanish, and
 * because every later frame reads the same cached instance, only a relog cures it.
 *
 * <h2>Why this is a healing loop and not a constructor hook</h2>
 * An earlier revision hooked the constructor. That fires on every pipeline, including
 * ones that are legitimately empty because DH is disabled in that dimension, and it
 * cannot tell a transient bail from a permanent one. Checking the <em>active</em>
 * pipeline once a second instead targets exactly the state that matters: a cached
 * instance that is empty at a moment when DH does want rendering. It is idempotent, so
 * it also repairs the case where DH is toggled off and on mid-session.
 *
 * <h2>Cost</h2>
 * One tick in twenty, a few cached reflective reads, and no work at all once the active
 * pipeline is healthy. Nothing runs on the render path.
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li>{@link #SUPPRESS} keeps our own {@code DHCompatInternal} construction from being
 *       mistaken for a fresh defect (an earlier revision re-queued itself every tick and
 *       wrote 5 GB of log lines).</li>
 *   <li>The attempt budget is keyed on the pipeline, so a stream of instances cannot
 *       reset it.</li>
 *   <li>Attempts are only spent when DH actually reports rendering enabled; retrying
 *       before that can never succeed.</li>
 *   <li>Logging is capped for the session. A misbehaving fix must not fill a disk.</li>
 * </ul>
 *
 * <p>Everything is reflective against Iris/DH names, so this jar compiles against nothing
 * but NeoForge and Minecraft, and a pack update degrades to a logged warning rather than a
 * startup crash.
 */
@EventBusSubscriber(modid = DhCompatFix.MODID, value = Dist.CLIENT)
public final class DhCompatFix {

    public static final String MODID = "dhcompatfix";
    private static final Logger LOG = LoggerFactory.getLogger("DHFix");

    private static final String C_INTERNAL = "net.irisshaders.iris.compat.dh.DHCompatInternal";
    private static final String C_COMPAT = "net.irisshaders.iris.compat.dh.DHCompat";
    private static final String C_PIPELINE = "net.irisshaders.iris.pipeline.IrisRenderingPipeline";
    private static final String C_IRIS = "net.irisshaders.iris.Iris";
    private static final String C_DH_DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";
    private static final String I_CONFIG = "com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig";
    private static final String I_GRAPHICS = "com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig";
    private static final String I_VALUE = "com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue";

    /** Per-pipeline attempt count, keyed on pipeline identity. */
    private static final Map<Object, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    /** Tick at which each pipeline may next be retried. */
    private static final Map<Object, Integer> NEXT_TRY = new ConcurrentHashMap<>();

    /** True while we construct our own replacement. */
    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final int CHECK_INTERVAL = 20;   // ticks: once per second
    private static final int MAX_ATTEMPTS = 3;
    private static final int[] BACKOFF_TICKS = {40, 200, 400}; // 2s, 10s, 20s

    private static final AtomicInteger LOG_BUDGET = new AtomicInteger(60);
    private static volatile boolean budgetExhausted;

    private static int ticks;
    private static boolean sawPipeline;
    private static boolean attached;
    private static boolean inertWarned;

    private DhCompatFix() {}

    // ---- cached reflective handles (resolved once) ----
    private static volatile Field F_PIPELINE, F_SOLID, F_TERRAIN_FB, F_GENERIC, F_INCOMPATIBLE,
            F_COMPAT_INTERNAL, F_DH_CONFIGS;
    private static volatile Constructor<?> CTOR;
    private static volatile Method M_PIPELINE_MANAGER, M_PIPELINE_NULLABLE, M_GET_DHCOMPAT,
            M_GET_INSTANCE, M_GRAPHICS, M_RENDERING_ENABLED, M_GET_VALUE;
    private static volatile Class<?> C_PIPELINE_CLASS;

    private static void logWarn(String fmt, Object... args) {
        if (LOG_BUDGET.getAndDecrement() > 0) {
            LOG.warn(fmt, args);
        } else if (!budgetExhausted) {
            budgetExhausted = true;
            LOG.warn("[DHFIX] log budget exhausted - further DHFIX output suppressed for this session");
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        ticks++;
        if (ticks % CHECK_INTERVAL != 0) return;
        try {
            check();
        } catch (Throwable t) {
            logWarn("[DHFIX] check threw: {}", describe(t));
        }
    }

    private static void check() throws Exception {
        Object pipeline = activePipeline();
        if (pipeline == null) {
            if (ticks > 400 && !sawPipeline && !inertWarned) {
                inertWarned = true;
                logWarn("[DHFIX] no Iris rendering pipeline after {} ticks - fix is inert", ticks);
            }
            return;
        }
        sawPipeline = true;
        if (!attached) {
            attached = true;
            logWarn("[DHFIX] attached to pipeline {} (dhRenderingEnabled={}) - watching for a stale compat instance",
                    id(pipeline), dhRenderingEnabled());
        }

        Object compat = compat(pipeline);
        if (compat == null) return;

        Object instance = instance(compat);
        if (instance == null) return;

        if (!isEmpty(instance) || isIncompatible(instance)) return; // healthy, or deliberately off
        if (!dhRenderingEnabled()) return;                          // legitimately nothing to render

        int attempt = ATTEMPTS.getOrDefault(pipeline, 0);
        if (attempt >= MAX_ATTEMPTS) {
            if (!isIncompatible(instance)) degrade(instance, "rebuild exhausted");
            return;
        }
        Integer due = NEXT_TRY.get(pipeline);
        if (due != null && ticks < due) return;

        rebuild(pipeline, compat, instance, attempt);
    }

    private static void rebuild(Object pipeline, Object compat, Object stale, int attempt) {
        try {
            boolean shadow = dhShadowEnabled(pipeline);
            Object fresh;
            SUPPRESS.set(Boolean.TRUE);
            try {
                fresh = ctor().newInstance(pipeline, shadow);
            } finally {
                SUPPRESS.set(Boolean.FALSE);
            }

            if (internal("solidProgram").get(fresh) == null) {
                attempt++;
                ATTEMPTS.put(pipeline, attempt);
                if (attempt < MAX_ATTEMPTS) NEXT_TRY.put(pipeline, ticks + BACKOFF_TICKS[attempt]);
                logWarn("[DHFIX] rebuild attempt {}/{} still produced no program - deferring", attempt, MAX_ATTEMPTS);
                if (attempt >= MAX_ATTEMPTS) degrade(stale, "rebuild produced no program");
                return;
            }

            compatInternal().set(compat, fresh);
            ATTEMPTS.remove(pipeline);
            NEXT_TRY.remove(pipeline);
            logWarn("[DHFIX] repaired stale DH compat on pipeline {} (shadow={}) - LODs restored", id(pipeline), shadow);
        } catch (Throwable t) {
            attempt++;
            ATTEMPTS.put(pipeline, attempt);
            if (attempt < MAX_ATTEMPTS) NEXT_TRY.put(pipeline, ticks + BACKOFF_TICKS[attempt]);
            logWarn("[DHFIX] rebuild failed ({}/{}): {}", attempt, MAX_ATTEMPTS, describe(t));
            if (attempt >= MAX_ATTEMPTS) degrade(stale, "rebuild threw");
        }
    }

    /**
     * Give up honestly: flag incompatible so Iris stops pretending DH is wired up, and DH
     * falls back to its own renderer. Unshaded LODs beat no LODs.
     */
    private static void degrade(Object instance, String why) {
        try {
            internal("incompatible").set(instance, true);
            logWarn("[DHFIX] {} - marked incompatible so DH degrades to its own renderer", why);
        } catch (Throwable t) {
            logWarn("[DHFIX] could not mark incompatible: {}", describe(t));
        }
    }

    /** Unwrap reflection wrappers so the log carries the exception that actually happened. */
    private static String describe(Throwable t) {
        Throwable c = t;
        while ((c instanceof InvocationTargetException || c instanceof ExceptionInInitializerError)
                && c.getCause() != null) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return c.getClass().getName() + (m == null ? "" : ": " + m);
    }

    // ---- state accessors ----

    private static Object activePipeline() throws Exception {
        Method pm = M_PIPELINE_MANAGER;
        if (pm == null) {
            pm = Class.forName(C_IRIS).getMethod("getPipelineManager");
            pm.setAccessible(true);
            M_PIPELINE_MANAGER = pm;
        }
        Object manager = pm.invoke(null);
        if (manager == null) return null;

        Method pn = M_PIPELINE_NULLABLE;
        if (pn == null) {
            pn = manager.getClass().getMethod("getPipelineNullable");
            pn.setAccessible(true);
            M_PIPELINE_NULLABLE = pn;
        }
        Object pipeline = pn.invoke(manager);
        if (pipeline == null) return null;

        Class<?> cls = C_PIPELINE_CLASS;
        if (cls == null) {
            cls = Class.forName(C_PIPELINE);
            C_PIPELINE_CLASS = cls;
        }
        // Shadow/other pipeline implementations carry no DHCompat; only the main pipeline does.
        return cls.isInstance(pipeline) ? pipeline : null;
    }

    private static Object compat(Object pipeline) throws Exception {
        Method m = M_GET_DHCOMPAT;
        if (m == null) {
            m = Class.forName(C_PIPELINE).getMethod("getDHCompat");
            m.setAccessible(true);
            M_GET_DHCOMPAT = m;
        }
        return m.invoke(pipeline);
    }

    private static Object instance(Object compat) throws Exception {
        Method m = M_GET_INSTANCE;
        if (m == null) {
            m = Class.forName(C_COMPAT).getMethod("getInstance");
            m.setAccessible(true);
            M_GET_INSTANCE = m;
        }
        return m.invoke(compat);
    }

    private static boolean isEmpty(Object instance) throws Exception {
        return internal("solidProgram").get(instance) == null
                && internal("dhTerrainFramebuffer").get(instance) == null
                && internal("genericShader").get(instance) == null;
    }

    private static boolean isIncompatible(Object instance) throws Exception {
        return (Boolean) internal("incompatible").get(instance);
    }

    /** Readiness means DH wants rendering. Configs merely existing is not enough. */
    private static boolean dhRenderingEnabled() {
        try {
            Field f = F_DH_CONFIGS;
            if (f == null) {
                f = Class.forName(C_DH_DELAYED).getDeclaredField("configs");
                f.setAccessible(true);
                F_DH_CONFIGS = f;
            }
            Object configs = f.get(null);
            if (configs == null) return false;

            Method g = M_GRAPHICS;
            if (g == null) {
                g = Class.forName(I_CONFIG).getMethod("graphics");
                g.setAccessible(true);
                M_GRAPHICS = g;
            }
            Object graphics = g.invoke(configs);
            if (graphics == null) return false;

            Method r = M_RENDERING_ENABLED;
            if (r == null) {
                r = Class.forName(I_GRAPHICS).getMethod("renderingEnabled");
                r.setAccessible(true);
                M_RENDERING_ENABLED = r;
            }
            Object value = r.invoke(graphics);
            if (value == null) return false;

            Method v = M_GET_VALUE;
            if (v == null) {
                v = Class.forName(I_VALUE).getMethod("getValue");
                v.setAccessible(true);
                M_GET_VALUE = v;
            }
            return Boolean.TRUE.equals(v.invoke(value));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean dhShadowEnabled(Object pipeline) {
        try {
            Object directives = call(pipeline, "getPackDirectives");
            Object shadow = call(directives, "getShadowDirectives");
            Object ob = call(shadow, "isDhShadowEnabled");
            return (Boolean) call(ob, "orElse", new Class<?>[]{boolean.class}, new Object[]{true});
        } catch (Throwable t) {
            // Unknown is not the same as false: assume true so DH shadows are not silently lost.
            return true;
        }
    }

    // ---- reflective plumbing ----

    private static Field internal(String name) throws Exception {
        Field cached = switch (name) {
            case "pipeline" -> F_PIPELINE;
            case "solidProgram" -> F_SOLID;
            case "dhTerrainFramebuffer" -> F_TERRAIN_FB;
            case "genericShader" -> F_GENERIC;
            case "incompatible" -> F_INCOMPATIBLE;
            default -> null;
        };
        if (cached != null) return cached;
        Field f = Class.forName(C_INTERNAL).getDeclaredField(name);
        f.setAccessible(true);
        switch (name) {
            case "pipeline" -> F_PIPELINE = f;
            case "solidProgram" -> F_SOLID = f;
            case "dhTerrainFramebuffer" -> F_TERRAIN_FB = f;
            case "genericShader" -> F_GENERIC = f;
            case "incompatible" -> F_INCOMPATIBLE = f;
            default -> { }
        }
        return f;
    }

    private static Field compatInternal() throws Exception {
        Field f = F_COMPAT_INTERNAL;
        if (f == null) {
            f = Class.forName(C_COMPAT).getDeclaredField("compatInternalInstance");
            f.setAccessible(true);
            F_COMPAT_INTERNAL = f;
        }
        return f;
    }

    private static Constructor<?> ctor() throws Exception {
        Constructor<?> c = CTOR;
        if (c == null) {
            c = Class.forName(C_INTERNAL).getDeclaredConstructor(Class.forName(C_PIPELINE), boolean.class);
            c.setAccessible(true);
            CTOR = c;
        }
        return c;
    }

    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object[] args) throws Exception {
        Method m = target.getClass().getMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static String id(Object pipeline) {
        return pipeline == null ? "null" : Integer.toHexString(System.identityHashCode(pipeline));
    }
}
