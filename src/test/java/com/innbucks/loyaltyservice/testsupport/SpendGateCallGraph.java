package com.innbucks.loyaltyservice.testsupport;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A static call graph of the service's own bytecode, built to answer one
 * question: which controller call sites reach the spend gate
 * ({@code UserService.spendabilityOf}) WITHOUT going through
 * {@code EligibilityDeferral.run}?
 *
 * <p>Bytecode rather than reflection or a hand-kept list, because the property
 * is about call sites: a new endpoint, a new service method that reaches the
 * gate, or a new caller of an existing one all show up here without anyone
 * remembering to register them.
 *
 * <ul>
 *   <li>Edges are every {@code invoke*} instruction, plus every lambda /
 *       method-reference creation ({@code invokedynamic} through
 *       {@code LambdaMetafactory}, edge to the implementation method), plus
 *       supertype → override (so a call through an interface or base class
 *       reaches the implementation).</li>
 *   <li>"Reaches the gate" = backward closure from the gate over those edges.</li>
 *   <li>A lambda is "deferred" when the instruction right after its
 *       {@code invokedynamic} is a call to {@code EligibilityDeferral.run} —
 *       i.e. it is exactly the supplier handed to {@code run}.</li>
 *   <li>A controller call into a gate-reaching method of another class is
 *       wrapped only when it is made from inside a deferred lambda (or is a
 *       method reference that is itself deferred).</li>
 * </ul>
 */
public final class SpendGateCallGraph {

    public static final String GATE_OWNER = "com/innbucks/loyaltyservice/service/UserService";
    public static final String GATE_NAME = "spendabilityOf";
    public static final String DEFERRAL_OWNER = "com/innbucks/loyaltyservice/service/EligibilityDeferral";
    public static final String DEFERRAL_RUN = "run";

    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "Lorg/springframework/web/bind/annotation/RestController;",
            "Lorg/springframework/stereotype/Controller;");
    /** Annotations that make a method an entry point nobody wraps. */
    public static final Set<String> UNWRAPPED_ENTRY_ANNOTATIONS = Set.of(
            "Lorg/springframework/scheduling/annotation/Scheduled;",
            "Lorg/springframework/scheduling/annotation/Async;",
            "Lorg/springframework/context/event/EventListener;",
            "Lorg/springframework/transaction/event/TransactionalEventListener;");

    /** One call site: {@code viaLambdaFactory} when it is a lambda/method-ref creation. */
    record Call(String owner, String name, String desc, boolean viaLambdaFactory) {
        String key() {
            return owner + "." + name + desc;
        }
    }

    static final class MethodInfo {
        final String owner;
        final String name;
        final String desc;
        final List<Call> calls = new ArrayList<>();
        final Set<String> annotations = new HashSet<>();

        MethodInfo(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        String key() {
            return owner + "." + name + desc;
        }
    }

    static final class ClassInfo {
        final String name;
        String superName;
        String[] interfaces = new String[0];
        boolean controller;
        final Map<String, MethodInfo> methods = new HashMap<>();
        /** Keys of the lambda/method-ref implementations handed straight to EligibilityDeferral.run. */
        final Set<String> deferred = new HashSet<>();

        ClassInfo(String name) {
            this.name = name;
        }
    }

    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Set<String> extraControllers = new HashSet<>();
    private Set<String> reachesGate;

    /** Every main (non-test) class of the service. */
    public static SpendGateCallGraph ofMainClasses() throws IOException {
        SpendGateCallGraph g = new SpendGateCallGraph();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/innbucks/loyaltyservice/**/*.class");
        for (Resource r : resources) {
            String url = r.getURL().toString();
            if (url.contains("/test-classes/")) {
                continue;
            }
            try (InputStream in = r.getInputStream()) {
                g.add(in.readAllBytes(), false);
            }
        }
        return g;
    }

    /** Adds a class (e.g. a test fixture), optionally treating it as a controller. */
    public SpendGateCallGraph addClass(Class<?> type, boolean treatAsController) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no bytecode for " + type.getName());
            }
            add(in.readAllBytes(), treatAsController);
        }
        reachesGate = null;
        return this;
    }

    private void add(byte[] bytes, boolean treatAsController) {
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            ClassInfo info;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                info = new ClassInfo(name);
                info.superName = superName;
                info.interfaces = interfaces == null ? new String[0] : interfaces;
                classes.put(name, info);
                if (treatAsController) {
                    extraControllers.add(name);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (CONTROLLER_ANNOTATIONS.contains(descriptor)) {
                    info.controller = true;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodInfo m = new MethodInfo(info.name, name, descriptor);
                info.methods.put(name + descriptor, m);
                return new MethodVisitor(Opcodes.ASM9) {
                    /** The lambda created by the last instruction, waiting to see who consumes it. */
                    Call pendingLambda;

                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        m.annotations.add(desc);
                        return null;
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        pendingLambda = null;
                        if (LAMBDA_FACTORY.equals(bsm.getOwner()) && args.length > 1
                                && args[1] instanceof Handle impl) {
                            Call c = new Call(impl.getOwner(), impl.getName(), impl.getDesc(), true);
                            m.calls.add(c);
                            pendingLambda = c;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        if (pendingLambda != null && DEFERRAL_OWNER.equals(owner) && DEFERRAL_RUN.equals(n)) {
                            info.deferred.add(pendingLambda.key());
                        }
                        pendingLambda = null;
                        m.calls.add(new Call(owner, n, d, false));
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        pendingLambda = null;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        pendingLambda = null;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String n, String d) {
                        pendingLambda = null;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        pendingLambda = null;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private boolean isController(String owner) {
        ClassInfo c = classes.get(owner);
        return extraControllers.contains(owner) || (c != null && c.controller);
    }

    /** The declaring method a call resolves to, walking up scanned superclasses; null if outside the scan. */
    private MethodInfo resolve(String owner, String name, String desc) {
        String cur = owner;
        while (cur != null) {
            ClassInfo c = classes.get(cur);
            if (c == null) {
                return null;
            }
            MethodInfo m = c.methods.get(name + desc);
            if (m != null) {
                return m;
            }
            cur = c.superName;
        }
        return null;
    }

    /** Keys of every method that can reach the gate. */
    public Set<String> reachesGate() {
        if (reachesGate != null) {
            return reachesGate;
        }
        // Reverse edges: callee key -> caller keys.
        Map<String, Set<String>> callers = new HashMap<>();
        for (ClassInfo c : classes.values()) {
            for (MethodInfo m : c.methods.values()) {
                for (Call call : m.calls) {
                    MethodInfo target = resolve(call.owner(), call.name(), call.desc());
                    if (target != null) {
                        callers.computeIfAbsent(target.key(), k -> new HashSet<>()).add(m.key());
                    }
                }
                // A call through a supertype reaches this override.
                if (!m.name.startsWith("<")) {
                    List<String> supers = new ArrayList<>(List.of(c.interfaces));
                    if (c.superName != null) {
                        supers.add(c.superName);
                    }
                    for (String s : supers) {
                        MethodInfo sm = resolve(s, m.name, m.desc);
                        if (sm != null) {
                            callers.computeIfAbsent(m.key(), k -> new HashSet<>()).add(sm.key());
                        }
                    }
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>();
        ClassInfo gate = classes.get(GATE_OWNER);
        if (gate != null) {
            for (MethodInfo m : gate.methods.values()) {
                if (m.name.equals(GATE_NAME)) {
                    work.add(m.key());
                }
            }
        }
        while (!work.isEmpty()) {
            String k = work.poll();
            if (seen.add(k)) {
                work.addAll(callers.getOrDefault(k, Set.of()));
            }
        }
        reachesGate = seen;
        return seen;
    }

    /**
     * Controller call sites into a gate-reaching method of a non-controller
     * class that do not go through {@code EligibilityDeferral.run}, as
     * {@code "Controller.method -> Service.method"}.
     */
    public Set<String> unwrappedControllerCalls() {
        Set<String> r = reachesGate();
        Set<String> out = new TreeSet<>();
        for (ClassInfo c : classes.values()) {
            if (!isController(c.name)) {
                continue;
            }
            for (MethodInfo m : c.methods.values()) {
                for (Call call : m.calls) {
                    if (isController(call.owner())) {
                        continue; // a controller's own lambdas/helpers are checked at THEIR calls
                    }
                    MethodInfo target = resolve(call.owner(), call.name(), call.desc());
                    if (target == null || !r.contains(target.key())) {
                        continue;
                    }
                    boolean wrapped = call.viaLambdaFactory()
                            ? c.deferred.contains(call.key())
                            : c.deferred.contains(m.key());
                    if (!wrapped) {
                        out.add(simple(c.name) + "." + m.name + " -> " + simple(target.owner) + "." + target.name);
                    }
                }
            }
        }
        return out;
    }

    /** Controllers holding at least one gate-reaching call that IS wrapped. */
    public Set<String> controllersWithWrappedSpends() {
        Set<String> r = reachesGate();
        Set<String> out = new TreeSet<>();
        for (ClassInfo c : classes.values()) {
            if (!isController(c.name)) {
                continue;
            }
            for (MethodInfo m : c.methods.values()) {
                if (!c.deferred.contains(m.key())) {
                    continue;
                }
                for (Call call : m.calls) {
                    MethodInfo target = resolve(call.owner(), call.name(), call.desc());
                    if (target != null && r.contains(target.key()) && !isController(call.owner())) {
                        out.add(simple(c.name));
                    }
                }
            }
        }
        return out;
    }

    /** Gate-reaching methods carrying an entry-point annotation nobody wraps. */
    public Set<String> unwrappedEntryPoints() {
        Set<String> r = reachesGate();
        Set<String> out = new TreeSet<>();
        for (ClassInfo c : classes.values()) {
            for (MethodInfo m : c.methods.values()) {
                if (r.contains(m.key()) && m.annotations.stream().anyMatch(UNWRAPPED_ENTRY_ANNOTATIONS::contains)) {
                    out.add(simple(c.name) + "." + m.name);
                }
            }
        }
        return out;
    }

    /** True when the named method (any descriptor) reaches the gate. */
    public boolean reachesGate(String owner, String name) {
        return reachesGate().stream().anyMatch(k -> k.startsWith(owner + "." + name + "("));
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
