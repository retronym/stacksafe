package stacksafe;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static org.objectweb.asm.Opcodes.*;

/**
 * Rewrites each {@code @StackSafe} method {@code m(args)} into
 * <ol>
 *   <li>a twin {@code m$ss(args, int depth)} whose body is the original, with a yield check on entry and
 *       calls to other {@code @StackSafe} methods redirected to their twins with {@code depth + 1};</li>
 *   <li>a thunk {@code m$ss$thunk(args)} that calls the twin with depth 0 and boxes the result;</li>
 *   <li>a new {@code m(args)} that hands the thunk to {@link StackSafeRuntime#run}.</li>
 * </ol>
 */
public final class StackSafeTransformer implements ClassFileTransformer {
    public static final int DEFAULT_INTERVAL = 1024;

    private static final String ANNOTATION = "Lstacksafe/StackSafe;";
    private static final byte[] ANNOTATION_NEEDLE = "stacksafe/StackSafe;".getBytes(StandardCharsets.US_ASCII);
    private static final String RUNTIME = "stacksafe/StackSafeRuntime";
    private static final String SUPPLIER = "java/util/function/Supplier";
    private static final String OBJECT = "java/lang/Object";
    private static final String TWIN_SUFFIX = "$ss";
    private static final String THUNK_SUFFIX = "$ss$thunk";
    private static final String BODY_SUFFIX = "$ss$body";
    private static final String DEPTH_FIELD_DESC = "Ljava/lang/ThreadLocal;";
    private static final String THREAD_LOCAL_ENUM = "THREAD_LOCAL";
    /** Marks a {@code Depth.THREAD_LOCAL} method in the access word kept in {@link ClassInfo#annotated}. */
    private static final int TL_FLAG = 1 << 30;
    private static final Type OBJECT_TYPE = Type.getObjectType(OBJECT);
    private static final Type NO_ARG_OBJECT = Type.getMethodType(OBJECT_TYPE);
    private static final Handle LAMBDA_METAFACTORY = new Handle(H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory", "metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
            + "Ljava/lang/invoke/CallSite;", false);
    private static final Object BOOTSTRAP_LOADER = new Object();

    /** What we need to know about a class other than the one being transformed. */
    private record ClassInfo(int access, String superName, Map<String, Integer> annotated) {
        static final ClassInfo MISSING = new ClassInfo(0, null, Map.of());
        boolean isInterface() { return (access & ACC_INTERFACE) != 0; }
    }

    private final int mask;
    private final Map<Object, Map<String, ClassInfo>> infoCache = Collections.synchronizedMap(new WeakHashMap<>());

    public StackSafeTransformer() { this(DEFAULT_INTERVAL); }

    /** @param interval recursive calls between yields; a power of two */
    public StackSafeTransformer(int interval) {
        if (interval < 1 || Integer.bitCount(interval) != 1)
            throw new IllegalArgumentException("interval must be a power of two: " + interval);
        this.mask = interval - 1;
    }

    // ---- agent entry point ----

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (redefined != null || (className != null && className.startsWith("stacksafe/StackSafe"))) return null;
        try {
            return transform(loader, bytes);
        } catch (Throwable t) {
            System.err.println("stacksafe: failed to transform " + className + ": " + t);
            return null;
        }
    }

    /** @return the rewritten class, or {@code null} if it has no {@code @StackSafe} methods. */
    public byte[] transform(ClassLoader loader, byte[] bytes) {
        if (indexOf(bytes, ANNOTATION_NEEDLE) < 0) return null;
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);

        Set<String> existing = new HashSet<>();
        for (MethodNode m : cn.methods) existing.add(m.name + m.desc);
        List<MethodNode> targets = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (annotatedAccess(m) == null) continue;
            if (m.name.startsWith("<") || (m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
                System.err.println("stacksafe: ignoring @StackSafe on " + cn.name + "." + m.name + " (no body to rewrite)");
            } else if (!existing.contains(m.name + TWIN_SUFFIX + twinDesc(m.desc))
                    && !existing.contains(m.name + BODY_SUFFIX + m.desc)) {
                targets.add(m);
            }
        }
        if (targets.isEmpty()) return null;

        Map<String, Integer> annotated = new HashMap<>();
        for (MethodNode m : targets) annotated.put(m.name + m.desc, annotatedAccess(m));
        ClassInfo own = new ClassInfo(cn.access, cn.superName, annotated);
        Resolver resolver = new Resolver(loader, cn.name, own);

        boolean itf = (cn.access & ACC_INTERFACE) != 0;
        List<MethodNode> generated = new ArrayList<>();
        for (MethodNode m : targets) {
            if ((annotatedAccess(m) & TL_FLAG) != 0) rewriteThreadLocal(cn, m, itf, generated);
            else rewrite(cn, m, itf, resolver, generated);
        }
        cn.methods.addAll(generated);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) { return resolver.commonSuperClass(a, b); }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ---- per-method rewrite ----

    private void rewrite(ClassNode cn, MethodNode m, boolean itf, Resolver resolver, List<MethodNode> out) {
        String origName = m.name, origDesc = m.desc;
        int origAccess = m.access;
        boolean isStatic = (origAccess & ACC_STATIC) != 0;
        String twinName = origName + TWIN_SUFFIX;
        String twinDesc = twinDesc(origDesc);
        String thunkName = origName + THUNK_SUFFIX;
        Type ret = Type.getReturnType(origDesc);

        List<Type> captured = new ArrayList<>();
        if (!isStatic) captured.add(Type.getObjectType(cn.name));
        captured.addAll(List.of(Type.getArgumentTypes(origDesc)));
        Type[] capturedArr = captured.toArray(new Type[0]);
        String thunkDesc = Type.getMethodDescriptor(OBJECT_TYPE, capturedArr);

        // 1. the public face: same signature, annotations and generics, body delegates to run()
        MethodNode wrapper = new MethodNode(ASM9, origAccess & ~ACC_SYNCHRONIZED, origName, origDesc, m.signature,
            m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
        moveMetadata(m, wrapper);
        int slot = (Type.getArgumentsAndReturnSizes(origDesc) >> 2) - (isStatic ? 1 : 0);
        int invokeTwin = isStatic ? INVOKESTATIC : INVOKESPECIAL;   // non-virtual: this class's twin, even via super.m()
        InsnList call = loads(capturedArr);
        call.add(new InsnNode(ICONST_0));
        call.add(new MethodInsnNode(invokeTwin, cn.name, twinName, twinDesc, itf));
        buildEntry(wrapper, cn, capturedArr, thunkName, thunkDesc, itf, ret, slot, call);
        out.add(wrapper);

        // 2. the thunk
        int thunkAccess = ACC_STATIC | ACC_SYNTHETIC | (itf ? ACC_PUBLIC : ACC_PRIVATE);
        MethodNode thunk = new MethodNode(ASM9, thunkAccess, thunkName, thunkDesc, null, null);
        InsnList t = loads(capturedArr);
        t.add(new InsnNode(ICONST_0));
        t.add(new MethodInsnNode(invokeTwin, cn.name, twinName, twinDesc, itf));
        box(t, ret);
        t.add(new InsnNode(ARETURN));
        thunk.instructions = t;
        out.add(thunk);

        // 3. the original method becomes the twin
        int depthSlot = slot;
        m.name = twinName;
        m.desc = twinDesc;
        m.access = (origAccess | ACC_SYNTHETIC) & ~(ACC_VARARGS | ACC_BRIDGE);
        m.signature = null;
        m.parameters = null;
        m.visibleAnnotations = m.invisibleAnnotations = null;
        m.visibleTypeAnnotations = m.invisibleTypeAnnotations = null;
        m.visibleParameterAnnotations = m.invisibleParameterAnnotations = null;
        m.visibleLocalVariableAnnotations = m.invisibleLocalVariableAnnotations = null;
        shiftLocals(m, depthSlot);
        redirectCalls(cn, m, depthSlot, resolver);
        m.instructions.insert(entryCheck(depthSlot));
    }

    /**
     * {@code Depth.THREAD_LOCAL}: the method keeps its signature and gains a prologue; the original body moves to
     * a private {@code m$ss$body}. Nothing at any call site changes.
     * <pre>
     * m(args) {
     *   int[] d = StackSafeRuntime.DEPTH.get();
     *   if (d == null) return run(() -> m$ss$body(args));       // outermost call: start a segment
     *   enter(d, mask);                                          // ++depth, yield every interval
     *   try { return m$ss$body(args); } finally { leave(d); }    // --depth
     * }
     * </pre>
     */
    private void rewriteThreadLocal(ClassNode cn, MethodNode m, boolean itf, List<MethodNode> out) {
        String name = m.name, desc = m.desc;
        int access = m.access;
        boolean isStatic = (access & ACC_STATIC) != 0;
        String bodyName = name + BODY_SUFFIX;
        String thunkName = name + THUNK_SUFFIX;
        Type ret = Type.getReturnType(desc);
        List<Type> captured = new ArrayList<>();
        if (!isStatic) captured.add(Type.getObjectType(cn.name));
        captured.addAll(List.of(Type.getArgumentTypes(desc)));
        Type[] capturedArr = captured.toArray(new Type[0]);
        String thunkDesc = Type.getMethodDescriptor(OBJECT_TYPE, capturedArr);
        int slot = (Type.getArgumentsAndReturnSizes(desc) >> 2) - (isStatic ? 1 : 0);
        int invokeBody = isStatic ? INVOKESTATIC : INVOKESPECIAL;

        MethodNode wrapper = new MethodNode(ASM9, access & ~ACC_SYNCHRONIZED, name, desc, m.signature,
            m.exceptions == null ? null : m.exceptions.toArray(new String[0]));
        moveMetadata(m, wrapper);

        InsnList call = loads(capturedArr);
        call.add(new MethodInsnNode(invokeBody, cn.name, bodyName, desc, itf));
        buildEntry(wrapper, cn, capturedArr, thunkName, thunkDesc, itf, ret, slot, call);
        out.add(wrapper);

        MethodNode thunk = new MethodNode(ASM9, ACC_STATIC | ACC_SYNTHETIC | (itf ? ACC_PUBLIC : ACC_PRIVATE),
            thunkName, thunkDesc, null, null);
        InsnList t = loads(capturedArr);
        t.add(new MethodInsnNode(invokeBody, cn.name, bodyName, desc, itf));
        box(t, ret);
        t.add(new InsnNode(ARETURN));
        thunk.instructions = t;
        out.add(thunk);

        m.name = bodyName;
        m.access = ((access & ~(ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE | ACC_VARARGS | ACC_BRIDGE)) | ACC_SYNTHETIC
            | (itf ? ACC_PUBLIC : ACC_PRIVATE));
        m.signature = null;
        m.parameters = null;
    }

    /**
     * The entry-point body shared by both modes. {@code call} pushes the arguments and invokes the real work.
     * <pre>
     * int[] d = StackSafeRuntime.DEPTH.get();
     * if (d == null) return run(() -> thunk(args));      // not on a stack-safe virtual thread yet: start a segment
     * enter(d, mask);                                    // already inside one: count this call, yield every interval
     * try { return CALL; } finally { leave(d); }
     * </pre>
     */
    private void buildEntry(MethodNode wrapper, ClassNode cn, Type[] captured, String thunkName, String thunkDesc,
                            boolean itf, Type ret, int slot, InsnList call) {
        LabelNode run = new LabelNode(), tryStart = new LabelNode(), tryEnd = new LabelNode(), handler = new LabelNode();
        InsnList w = new InsnList();
        w.add(new FieldInsnNode(GETSTATIC, RUNTIME, "DEPTH", DEPTH_FIELD_DESC));
        w.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()L" + OBJECT + ";", false));
        w.add(new TypeInsnNode(CHECKCAST, "[I"));
        w.add(new VarInsnNode(ASTORE, slot));
        w.add(new VarInsnNode(ALOAD, slot));
        w.add(new JumpInsnNode(IFNULL, run));
        w.add(new VarInsnNode(ALOAD, slot));
        w.add(new LdcInsnNode(mask));
        w.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "enter", "([II)V", false));
        w.add(tryStart);
        w.add(call);
        w.add(tryEnd);
        w.add(new VarInsnNode(ALOAD, slot));
        w.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "leave", "([I)V", false));
        w.add(new InsnNode(ret.getSort() == Type.VOID ? RETURN : ret.getOpcode(IRETURN)));
        w.add(handler);
        w.add(new VarInsnNode(ALOAD, slot));
        w.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "leave", "([I)V", false));
        w.add(new InsnNode(ATHROW));
        w.add(run);
        w.add(wrapperBody(cn, captured, thunkName, thunkDesc, itf, ret));
        wrapper.instructions = w;
        wrapper.tryCatchBlocks = new ArrayList<>(List.of(new TryCatchBlockNode(tryStart, tryEnd, handler, null)));
    }

    private static void moveMetadata(MethodNode from, MethodNode to) {
        to.parameters = from.parameters;
        to.visibleAnnotations = from.visibleAnnotations;
        to.invisibleAnnotations = from.invisibleAnnotations;
        to.visibleTypeAnnotations = from.visibleTypeAnnotations;
        to.invisibleTypeAnnotations = from.invisibleTypeAnnotations;
        to.visibleParameterAnnotations = from.visibleParameterAnnotations;
        to.invisibleParameterAnnotations = from.invisibleParameterAnnotations;
        to.visibleAnnotableParameterCount = from.visibleAnnotableParameterCount;
        to.invisibleAnnotableParameterCount = from.invisibleAnnotableParameterCount;
        from.visibleAnnotations = from.invisibleAnnotations = null;
        from.visibleTypeAnnotations = from.invisibleTypeAnnotations = null;
        from.visibleParameterAnnotations = from.invisibleParameterAnnotations = null;
        from.visibleLocalVariableAnnotations = from.invisibleLocalVariableAnnotations = null;
    }

    private InsnList wrapperBody(ClassNode cn, Type[] captured, String thunkName, String thunkDesc, boolean itf, Type ret) {
        InsnList w = loads(captured);
        Handle thunkHandle = new Handle(H_INVOKESTATIC, cn.name, thunkName, thunkDesc, itf);
        w.add(new InvokeDynamicInsnNode("get", Type.getMethodDescriptor(Type.getObjectType(SUPPLIER), captured),
            LAMBDA_METAFACTORY, NO_ARG_OBJECT, thunkHandle, NO_ARG_OBJECT));
        w.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "run", "(L" + SUPPLIER + ";)L" + OBJECT + ";", false));
        switch (ret.getSort()) {
            case Type.VOID -> {
                w.add(new InsnNode(POP));
                w.add(new InsnNode(RETURN));
            }
            case Type.ARRAY, Type.OBJECT -> {
                if (!ret.getInternalName().equals(OBJECT)) w.add(new TypeInsnNode(CHECKCAST, ret.getInternalName()));
                w.add(new InsnNode(ARETURN));
            }
            default -> {
                String box = boxClass(ret);
                w.add(new TypeInsnNode(CHECKCAST, box));
                w.add(new MethodInsnNode(INVOKEVIRTUAL, box, unboxMethod(ret), "()" + ret.getDescriptor(), false));
                w.add(new InsnNode(ret.getOpcode(IRETURN)));
            }
        }
        return w;
    }

    /** Locals at or above {@code depthSlot} move up one to make room for the depth parameter. */
    private static void shiftLocals(MethodNode m, int depthSlot) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof VarInsnNode v && v.var >= depthSlot) v.var++;
            else if (insn instanceof IincInsnNode i && i.var >= depthSlot) i.var++;
        }
        if (m.localVariables != null)
            for (LocalVariableNode lv : m.localVariables) if (lv.index >= depthSlot) lv.index++;
    }

    private void redirectCalls(ClassNode cn, MethodNode m, int depthSlot, Resolver resolver) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode mi) || mi.name.startsWith("<") || mi.owner.startsWith("[")) continue;
            if (!redirectable(cn, m, mi, resolver)) continue;
            InsnList depth = new InsnList();
            depth.add(new VarInsnNode(ILOAD, depthSlot));
            depth.add(new InsnNode(ICONST_1));
            depth.add(new InsnNode(IADD));
            m.instructions.insertBefore(mi, depth);
            mi.name = mi.name + TWIN_SUFFIX;
            mi.desc = twinDesc(mi.desc);
        }
    }

    private boolean redirectable(ClassNode cn, MethodNode caller, MethodInsnNode mi, Resolver resolver) {
        String key = mi.name + mi.desc;
        // The declaring class may be a superclass of the static receiver type.
        for (String c = mi.owner; c != null; c = resolver.get(c).superName()) {
            ClassInfo info = resolver.get(c);
            Integer access = info.annotated().get(key);
            if (access == null) continue;
            if ((access & TL_FLAG) != 0) return false;   // counts itself; calling the entry point is correct
            switch (mi.getOpcode()) {
                case INVOKESTATIC, INVOKESPECIAL -> { return true; }
                default -> {
                    if ((access & (ACC_PRIVATE | ACC_FINAL)) != 0 || (info.access() & ACC_FINAL) != 0) return true;
                    System.err.println("stacksafe: " + cn.name + "." + caller.name + " calls overridable @StackSafe method "
                        + c + "." + key + "; depth count restarts there (make it final/private/static, or use depth = THREAD_LOCAL)");
                    return false;
                }
            }
        }
        return false;
    }

    private InsnList entryCheck(int depthSlot) {
        LabelNode skip = new LabelNode();
        InsnList l = new InsnList();
        l.add(new VarInsnNode(ILOAD, depthSlot));
        l.add(new LdcInsnNode(mask));
        l.add(new InsnNode(IAND));
        l.add(new LdcInsnNode(mask));
        l.add(new JumpInsnNode(IF_ICMPNE, skip));
        l.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "yieldNow", "()V", false));
        l.add(skip);
        return l;
    }

    // ---- small helpers ----

    private static InsnList loads(Type[] types) {
        InsnList l = new InsnList();
        int slot = 0;
        for (Type t : types) {
            l.add(new VarInsnNode(t.getOpcode(ILOAD), slot));
            slot += t.getSize();
        }
        return l;
    }

    private static void box(InsnList l, Type t) {
        switch (t.getSort()) {
            case Type.VOID -> l.add(new InsnNode(ACONST_NULL));
            case Type.ARRAY, Type.OBJECT -> { }
            default -> l.add(new MethodInsnNode(INVOKESTATIC, boxClass(t), "valueOf",
                "(" + t.getDescriptor() + ")L" + boxClass(t) + ";", false));
        }
    }

    private static String boxClass(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> throw new IllegalArgumentException(t.toString());
        };
    }

    private static String unboxMethod(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "booleanValue";
            case Type.BYTE -> "byteValue";
            case Type.CHAR -> "charValue";
            case Type.SHORT -> "shortValue";
            case Type.INT -> "intValue";
            case Type.LONG -> "longValue";
            case Type.FLOAT -> "floatValue";
            case Type.DOUBLE -> "doubleValue";
            default -> throw new IllegalArgumentException(t.toString());
        };
    }

    private static String twinDesc(String desc) {
        int close = desc.indexOf(')');
        return desc.substring(0, close) + "I" + desc.substring(close);
    }

    /** The method's access word (plus {@link #TL_FLAG} for {@code Depth.THREAD_LOCAL}), or null if not annotated. */
    private static Integer annotatedAccess(MethodNode m) {
        AnnotationNode found = null;
        if (m.invisibleAnnotations != null)
            for (AnnotationNode a : m.invisibleAnnotations) if (ANNOTATION.equals(a.desc)) found = a;
        if (found == null && m.visibleAnnotations != null)
            for (AnnotationNode a : m.visibleAnnotations) if (ANNOTATION.equals(a.desc)) found = a;
        if (found == null) return null;
        boolean threadLocal = false;
        for (int i = 0; found.values != null && i + 1 < found.values.size(); i += 2)
            if ("depth".equals(found.values.get(i)) && found.values.get(i + 1) instanceof String[] e
                    && e.length == 2 && THREAD_LOCAL_ENUM.equals(e[1])) threadLocal = true;
        return m.access | (threadLocal ? TL_FLAG : 0);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ---- class metadata lookup (annotated-method index and hierarchy), without loading classes ----

    private final class Resolver {
        private final ClassLoader loader;
        private final String ownName;
        private final ClassInfo own;

        Resolver(ClassLoader loader, String ownName, ClassInfo own) {
            this.loader = loader;
            this.ownName = ownName;
            this.own = own;
        }

        ClassInfo get(String name) {
            if (name.equals(ownName)) return own;
            Map<String, ClassInfo> perLoader = infoCache.computeIfAbsent(
                loader == null ? BOOTSTRAP_LOADER : loader, k -> Collections.synchronizedMap(new HashMap<>()));
            ClassInfo cached = perLoader.get(name);
            if (cached == null) {
                cached = read(name);
                perLoader.put(name, cached);
            }
            return cached;
        }

        private ClassInfo read(String name) {
            String resource = name + ".class";
            try (InputStream in = loader != null ? loader.getResourceAsStream(resource)
                                                 : ClassLoader.getSystemResourceAsStream(resource)) {
                if (in == null) return ClassInfo.MISSING;
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                Map<String, Integer> annotated = new HashMap<>();
                for (MethodNode m : node.methods) {
                    Integer a = annotatedAccess(m);
                    if (a != null) annotated.put(m.name + m.desc, a);
                }
                return new ClassInfo(node.access, node.superName, annotated);
            } catch (IOException | IllegalArgumentException e) {
                // IllegalArgumentException: class file newer than this ASM understands (typically a JDK class).
                return reflect(name);
            }
        }

        /** Hierarchy only, for platform classes; they never carry {@code @StackSafe}. */
        private ClassInfo reflect(String name) {
            if (!(name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/"))) return ClassInfo.MISSING;
            try {
                Class<?> c = Class.forName(name.replace('/', '.'), false, null);
                Class<?> sup = c.getSuperclass();
                return new ClassInfo(c.getModifiers() | (c.isInterface() ? ACC_INTERFACE : 0),
                    sup == null ? null : sup.getName().replace('.', '/'), Map.of());
            } catch (ClassNotFoundException | LinkageError e) {
                return ClassInfo.MISSING;
            }
        }

        String commonSuperClass(String a, String b) {
            if (a.equals(b)) return a;
            if (get(a).isInterface() || get(b).isInterface()) return OBJECT;
            Set<String> chain = new HashSet<>();
            for (String c = a; c != null; c = get(c).superName()) chain.add(c);
            for (String c = b; c != null; c = get(c).superName()) if (chain.contains(c)) return c;
            return OBJECT;
        }
    }
}
