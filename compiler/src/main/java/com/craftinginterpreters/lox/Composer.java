package com.craftinginterpreters.lox;

import org.jetbrains.annotations.Nullable;
import proguard.classfile.*;
import proguard.classfile.attribute.Attribute;
import proguard.classfile.attribute.CodeAttribute;
import proguard.classfile.attribute.ParameterInfo;
import proguard.classfile.editor.ClassBuilder;
import proguard.classfile.editor.CodeAttributeComposer;
import proguard.classfile.editor.CompactCodeAttributeComposer;
import proguard.classfile.editor.ConstantPoolEditor;
import proguard.classfile.instruction.Instruction;
import proguard.classfile.util.InternalTypeEnumeration;
import proguard.resources.file.ResourceFile;

import java.sql.Array;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;
import static proguard.classfile.AccessConstants.PUBLIC;
import static proguard.classfile.AccessConstants.STATIC;
import static proguard.classfile.VersionConstants.CLASS_VERSION_1_8;
import static proguard.classfile.util.ClassUtil.*;

@SuppressWarnings("ALL")
public class Composer<T extends CompactCodeAttributeComposer> extends CompactCodeAttributeComposer {
    private final CompactCodeAttributeComposer delegate;

    public Composer(CompactCodeAttributeComposer delegate) {
        super(delegate.getTargetClass());
        this.delegate = delegate;
    }

    // Extensions

    // Useful for debugging purposes
    public CodeAttribute getCodeAttribute() {
        try {
            var field = delegate.getClass().getDeclaredField("codeAttributeComposer");
            field.setAccessible(true);
            var codeAttributeComposer = (CodeAttributeComposer)field.get(delegate);
            var code = codeAttributeComposer.getClass().getDeclaredField("code");
            code.setAccessible(true);
            var codeLength = codeAttributeComposer.getClass().getDeclaredField("codeLength");
            codeLength.setAccessible(true);
            return new CodeAttribute(0, 0, 0, (int)codeLength.get(codeAttributeComposer), (byte[])code.get(codeAttributeComposer), 0, null, 0, null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public T concat(Consumer<CompactCodeAttributeComposer>...composers) {
        // TODO: invokedynamic
        new_("java/lang/StringBuilder");
        dup();
        invokespecial("java/lang/StringBuilder", "<init>", "()V");
        Arrays.stream(composers).forEach(composer -> {
            composer.accept(this);
            invokevirtual("java/lang/Object", "toString", "()Ljava/lang/String;");
            invokevirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
        });
        return invokevirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
    }

    public T outline(ClassPool programClassPool, String className, String name, String descriptor, Consumer<CompactCodeAttributeComposer> composerConsumer) {
        var utilClass = programClassPool.getClass(className);
        if (utilClass == null) {
            utilClass = new ClassBuilder(
                    CLASS_VERSION_1_8,
                    PUBLIC,
                    className,
                    "java/lang/Object"
            ).getProgramClass();
            programClassPool.addClass(utilClass);
        }

        invokestatic(
            utilClass,
            utilClass.findMethod(name, descriptor) != null ? utilClass.findMethod(name, descriptor) : new ClassBuilder((ProgramClass) utilClass)
                .addAndReturnMethod(PUBLIC | STATIC, name, descriptor, 65_535, composer -> {
                    var enumeration = new InternalTypeEnumeration(descriptor);
                    var offset = 0;
                    while (enumeration.hasMoreTypes()) {
                        String type = enumeration.nextType();
                        switch(type) {
                            case "I", "B", "C", "S", "Z" -> composer.iload(offset);
                            case "D" -> composer.dload(offset);
                            case "F" -> composer.fload(offset);
                            case "J" -> composer.lload(offset);
                            default -> composer.aload(offset);
                        }
                        offset += internalTypeSize(type);
                    }
                    composerConsumer.accept(composer);
                    switch (internalMethodReturnType(descriptor)) {
                        case "I", "B", "C", "S", "Z" -> composer.ireturn();
                        case "D" -> composer.dreturn();
                        case "F" -> composer.freturn();
                        case "J" -> composer.lreturn();
                        case "V" -> composer.return_();
                        default -> composer.areturn();
                    }
                })
        );

        return (T) this;
    }

    public T box(String type) {
        boxPrimitiveType(internalPrimitiveTypeFromNumericClassName(type));
        return (T) this;
    }

    public T unbox(String type) {
        unboxPrimitiveType("Ljava/lang/Object;", internalPrimitiveTypeFromNumericClassName(type) + "");
        return (T) this;
    }

    public T instanceof_(String className) {
        return instanceof_(className, null);
    }

    public T boxed(String type, Function<T, T> block) {
        unbox(type);
        block.apply((T) this);
        return box(type);
    }

    public T FALSE() {
        return getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
    }

    public T TRUE() {
        return getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
    }


    public T switch_(int temporaryVarIndex, Function<SwitchBuilder, SwitchBuilder> switchBuilder) {
        switchBuilder.apply(new SwitchBuilder(temporaryVarIndex)).build((T) this);
        return (T) this;
    }

    public class SwitchBuilder {

        private class SwitchCase<X> {
            public Label label;
            public final X value;
            public final Function<T, T> composer;

            private SwitchCase(X value, Function<T, T> composer) {
                this.value = value;
                this.composer = composer;
            }
        }

        private final List<SwitchCase<?>> cases = new ArrayList<>();
        @Nullable private Function<T, T> defaultCase;
        private final int temporaryVarIndex;

        public SwitchBuilder(int temporaryVarIndex) {
            this.temporaryVarIndex = temporaryVarIndex;
        }

        public SwitchBuilder case_(String case_, Function<T, T> block) {
            cases.add(new SwitchCase<>(case_, block));
            return this;
        }

        public SwitchBuilder default_(Function<T, T> block) {
            this.defaultCase = block;
            return this;
        }

        void build(T composer) {
            var end = composer.createLabel();
            var default_ = this.defaultCase == null ? end : createLabel();
            composer
                .astore(temporaryVarIndex)
                .aload(temporaryVarIndex)
                .invokevirtual("java/lang/String", "hashCode", "()I");

            var lookupIndices =
                cases
                    .stream()
                    .peek(it -> it.label = composer.createLabel())
                    .collect(Collectors.groupingBy(switchCase -> switchCase.value.hashCode()));

            var lookupLabels = lookupIndices
                .entrySet()
                .stream()
                .collect(toMap(Map.Entry::getKey, e -> composer.createLabel(), (a, b) -> a, TreeMap::new));

            lookupswitch(
                default_,
                lookupLabels.keySet().stream().mapToInt(it -> it).toArray(),
                lookupLabels.values().toArray(new Label[0])
            );

            lookupIndices.forEach((valueIndex, cases) -> {
                composer.label(lookupLabels.get(valueIndex));
                Label nextLabel = null;
                for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
                    if (nextLabel != null) composer.label(nextLabel);

                    nextLabel = caseIndex == cases.size() - 1 ? default_ : composer.createLabel();

                    composer
                        .ldc(cases.get(caseIndex).value.toString())
                        .aload(temporaryVarIndex)
                        .invokevirtual("java/lang/Object", "equals", "(Ljava/lang/Object;)Z")
                        .ifeq(nextLabel)
                        .goto_(cases.get(caseIndex).label);
                }
            });

            cases.forEach(case_ -> {
                composer.label(case_.label);
                case_.composer.apply(composer);
            });

            if (this.defaultCase != null) {
                composer.label(default_);
                defaultCase.apply(composer);
            }

            composer.label(end);
        }
    }

    public T unpack(int n, BiFunction<T, Integer, T> action) {
        for (int i = 0; i < n; i++) {
            if (i != n - 1) dup();
            iconst(i);
            aaload();
            if (action == null) {
                if (i != n - 1) swap();
            } else {
                action.apply((T) this, i);
            }
        }
        return (T) this;
    }

    public T print(String s) {
        getstatic("java/lang/System", "out", "Ljava/io/PrintStream;");
        ldc(s);
        invokevirtual("java/io/PrintStream", "print", "(Ljava/lang/Object;)V");
        return (T) this;
    }

    public T println(String s) {
        getstatic("java/lang/System", "out", "Ljava/io/PrintStream;");
        ldc(s);
        invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V");
        return (T) this;
    }

    public T printlnpeek(String prefix) {
        dup();
        getstatic("java/lang/System", "out", "Ljava/io/PrintStream;");
        dup_x1();
        swap();
        if (prefix != null) {
            print(prefix);
        }
        invokevirtual("java/io/PrintStream", "print", "(Ljava/lang/Object;)V");
        ldc(" (stack top at offset " + this.getCodeLength() + ")");
        invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V");
        return (T) this;
    }

    // Overrides of the original composer

    @Override
    public ProgramClass getTargetClass() {
        return delegate.getTargetClass();
    }

    @Override
    public ConstantPoolEditor getConstantPoolEditor() {
        return delegate.getConstantPoolEditor();
    }

    @Override
    public int getCodeLength() {
        return delegate.getCodeLength();
    }

    @Override
    public void convertToTargetType(String sourceType, String targetType) {
        delegate.convertToTargetType(sourceType, targetType);
    }

    @Override
    public void boxPrimitiveType(char sourceType) {
        delegate.boxPrimitiveType(sourceType);
    }

    @Override
    public void convertPrimitiveType(char source, char target) {
        delegate.convertPrimitiveType(source, target);
    }

    @Override
    public void unboxPrimitiveType(String sourceType, String targetType) {
        delegate.unboxPrimitiveType(sourceType, targetType);
    }

    @Override
    public T reset() {
        delegate.reset();
        return (T)this;
    }

    @Override
    public T beginCodeFragment(int maximumCodeFragmentLength) {
        delegate.beginCodeFragment(maximumCodeFragmentLength);
        return (T)this;
    }

    @Override
    public Label createLabel() {
        return delegate.createLabel();
    }

    @Override
    public T label(Label label) {
        delegate.label(label);
        return (T)this;
    }

    @Override
    public T appendInstructions(Instruction[] instructions) {
        delegate.appendInstructions(instructions);
        return (T)this;
    }

    @Override
    public T appendInstruction(Instruction instruction) {
        delegate.appendInstruction(instruction);
        return (T)this;
    }

    @Override
    public T catchAll(Label startLabel, Label endLabel) {
        delegate.catchAll(startLabel, endLabel);
        return (T)this;
    }

    @Override
    public T catchAll(Label startLabel, Label endLabel, Label handlerLabel) {
        delegate.catchAll(startLabel, endLabel, handlerLabel);
        return (T)this;
    }

    @Override
    public T catch_(Label startLabel, Label endLabel, String catchType, Clazz referencedClass) {
        delegate.catch_(startLabel, endLabel, catchType, referencedClass);
        return (T)this;
    }

    @Override
    public T catch_(Label startLabel, Label endLabel, Label handlerLabel, String catchType, Clazz referencedClass) {
        delegate.catch_(startLabel, endLabel, handlerLabel, catchType, referencedClass);
        return (T)this;
    }

    @Override
    public T line(int lineNumber) {
        delegate.line(lineNumber);
        return (T)this;
    }

    @Override
    public T endCodeFragment() {
        delegate.endCodeFragment();
        return (T)this;
    }

    @Override
    public T nop() {
        delegate.nop();
        return (T)this;
    }

    @Override
    public T aconst_null() {
        delegate.aconst_null();
        return (T)this;
    }

    @Override
    public T iconst(int constant) {
        delegate.iconst(constant);
        return (T)this;
    }

    @Override
    public T iconst_m1() {
        delegate.iconst_m1();
        return (T)this;
    }

    @Override
    public T iconst_0() {
        delegate.iconst_0();
        return (T)this;
    }

    @Override
    public T iconst_1() {
        delegate.iconst_1();
        return (T)this;
    }

    @Override
    public T iconst_2() {
        delegate.iconst_2();
        return (T)this;
    }

    @Override
    public T iconst_3() {
        delegate.iconst_3();
        return (T)this;
    }

    @Override
    public T iconst_4() {
        delegate.iconst_4();
        return (T)this;
    }

    @Override
    public T iconst_5() {
        delegate.iconst_5();
        return (T)this;
    }

    @Override
    public T lconst(int constant) {
        delegate.lconst(constant);
        return (T)this;
    }

    @Override
    public T lconst_0() {
        delegate.lconst_0();
        return (T)this;
    }

    @Override
    public T lconst_1() {
        delegate.lconst_1();
        return (T)this;
    }

    @Override
    public T fconst(int constant) {
        delegate.fconst(constant);
        return (T)this;
    }

    @Override
    public T fconst_0() {
        delegate.fconst_0();
        return (T)this;
    }

    @Override
    public T fconst_1() {
        delegate.fconst_1();
        return (T)this;
    }

    @Override
    public T fconst_2() {
        delegate.fconst_2();
        return (T)this;
    }

    @Override
    public T dconst(int constant) {
        delegate.dconst(constant);
        return (T)this;
    }

    @Override
    public T dconst_0() {
        delegate.dconst_0();
        return (T)this;
    }

    @Override
    public T dconst_1() {
        delegate.dconst_1();
        return (T)this;
    }

    @Override
    public T bipush(int constant) {
        delegate.bipush(constant);
        return (T)this;
    }

    @Override
    public T sipush(int constant) {
        delegate.sipush(constant);
        return (T)this;
    }

    @Override
    public T ldc(int value) {
        delegate.ldc(value);
        return (T)this;
    }

    @Override
    public T ldc(float value) {
        delegate.ldc(value);
        return (T)this;
    }

    @Override
    public T ldc(String string) {
        delegate.ldc(string);
        return (T)this;
    }

    @Override
    public T ldc(Object primitiveArray) {
        delegate.ldc(primitiveArray);
        return (T)this;
    }

    @Override
    public T ldc(Clazz clazz, Member member) {
        delegate.ldc(clazz, member);
        return (T)this;
    }

    @Override
    public T ldc(String string, Clazz referencedClass, Member referencedMember) {
        delegate.ldc(string, referencedClass, referencedMember);
        return (T)this;
    }

    @Override
    public T ldc(ResourceFile resourceFile) {
        delegate.ldc(resourceFile);
        return (T)this;
    }

    @Override
    public T ldc(String string, ResourceFile referencedResourceFile) {
        delegate.ldc(string, referencedResourceFile);
        return (T)this;
    }

    @Override
    public T ldc(Clazz clazz) {
        delegate.ldc(clazz);
        return (T)this;
    }

    @Override
    public T ldc(String typeName, Clazz referencedClass) {
        delegate.ldc(typeName, referencedClass);
        return (T)this;
    }

    @Override
    public T ldc_(int constantIndex) {
        delegate.ldc_(constantIndex);
        return (T)this;
    }

    @Override
    public T ldc_w(int value) {
        delegate.ldc_w(value);
        return (T)this;
    }

    @Override
    public T ldc_w(float value) {
        delegate.ldc_w(value);
        return (T)this;
    }

    @Override
    public T ldc_w(String string) {
        delegate.ldc_w(string);
        return (T)this;
    }

    @Override
    public T ldc_w(Object primitiveArray) {
        delegate.ldc_w(primitiveArray);
        return (T)this;
    }

    @Override
    public T ldc_w(Clazz clazz, Member member) {
        delegate.ldc_w(clazz, member);
        return (T)this;
    }

    @Override
    public T ldc_w(String string, Clazz referencedClass, Member referencedMember) {
        delegate.ldc_w(string, referencedClass, referencedMember);
        return (T)this;
    }

    @Override
    public T ldc_w(ResourceFile resourceFile) {
        delegate.ldc_w(resourceFile);
        return (T)this;
    }

    @Override
    public T ldc_w(String string, ResourceFile referencedResourceFile) {
        delegate.ldc_w(string, referencedResourceFile);
        return (T)this;
    }

    @Override
    public T ldc_w(Clazz clazz) {
        delegate.ldc_w(clazz);
        return (T)this;
    }

    @Override
    public T ldc_w(String typeName, Clazz referencedClass) {
        delegate.ldc_w(typeName, referencedClass);
        return (T)this;
    }

    @Override
    public T ldc_w_(int constantIndex) {
        delegate.ldc_w_(constantIndex);
        return (T)this;
    }

    @Override
    public T ldc2_w(long value) {
        delegate.ldc2_w(value);
        return (T)this;
    }

    @Override
    public T ldc2_w(double value) {
        delegate.ldc2_w(value);
        return (T)this;
    }

    @Override
    public T ldc2_w(int constantIndex) {
        delegate.ldc2_w(constantIndex);
        return (T)this;
    }

    @Override
    public T iload(int variableIndex) {
        delegate.iload(variableIndex);
        return (T)this;
    }

    @Override
    public T lload(int variableIndex) {
        delegate.lload(variableIndex);
        return (T)this;
    }

    @Override
    public T fload(int variableIndex) {
        delegate.fload(variableIndex);
        return (T)this;
    }

    @Override
    public T dload(int variableIndex) {
        delegate.dload(variableIndex);
        return (T)this;
    }

    @Override
    public T aload(int variableIndex) {
        delegate.aload(variableIndex);
        return (T)this;
    }

    @Override
    public T iload_0() {
        delegate.iload_0();
        return (T)this;
    }

    @Override
    public T iload_1() {
        delegate.iload_1();
        return (T)this;
    }

    @Override
    public T iload_2() {
        delegate.iload_2();
        return (T)this;
    }

    @Override
    public T iload_3() {
        delegate.iload_3();
        return (T)this;
    }

    @Override
    public T lload_0() {
        delegate.lload_0();
        return (T)this;
    }

    @Override
    public T lload_1() {
        delegate.lload_1();
        return (T)this;
    }

    @Override
    public T lload_2() {
        delegate.lload_2();
        return (T)this;
    }

    @Override
    public T lload_3() {
        delegate.lload_3();
        return (T)this;
    }

    @Override
    public T fload_0() {
        delegate.fload_0();
        return (T)this;
    }

    @Override
    public T fload_1() {
        delegate.fload_1();
        return (T)this;
    }

    @Override
    public T fload_2() {
        delegate.fload_2();
        return (T)this;
    }

    @Override
    public T fload_3() {
        delegate.fload_3();
        return (T)this;
    }

    @Override
    public T dload_0() {
        delegate.dload_0();
        return (T)this;
    }

    @Override
    public T dload_1() {
        delegate.dload_1();
        return (T)this;
    }

    @Override
    public T dload_2() {
        delegate.dload_2();
        return (T)this;
    }

    @Override
    public T dload_3() {
        delegate.dload_3();
        return (T)this;
    }

    @Override
    public T aload_0() {
        delegate.aload_0();
        return (T)this;
    }

    @Override
    public T aload_1() {
        delegate.aload_1();
        return (T)this;
    }

    @Override
    public T aload_2() {
        delegate.aload_2();
        return (T)this;
    }

    @Override
    public T aload_3() {
        delegate.aload_3();
        return (T)this;
    }

    @Override
    public T iaload() {
        delegate.iaload();
        return (T)this;
    }

    @Override
    public T laload() {
        delegate.laload();
        return (T)this;
    }

    @Override
    public T faload() {
        delegate.faload();
        return (T)this;
    }

    @Override
    public T daload() {
        delegate.daload();
        return (T)this;
    }

    @Override
    public T aaload() {
        delegate.aaload();
        return (T)this;
    }

    @Override
    public T baload() {
        delegate.baload();
        return (T)this;
    }

    @Override
    public T caload() {
        delegate.caload();
        return (T)this;
    }

    @Override
    public T saload() {
        delegate.saload();
        return (T)this;
    }

    @Override
    public T istore(int variableIndex) {
        delegate.istore(variableIndex);
        return (T)this;
    }

    @Override
    public T lstore(int variableIndex) {
        delegate.lstore(variableIndex);
        return (T)this;
    }

    @Override
    public T fstore(int variableIndex) {
        delegate.fstore(variableIndex);
        return (T)this;
    }

    @Override
    public T dstore(int variableIndex) {
        delegate.dstore(variableIndex);
        return (T)this;
    }

    @Override
    public T astore(int variableIndex) {
        delegate.astore(variableIndex);
        return (T)this;
    }

    @Override
    public T istore_0() {
        delegate.istore_0();
        return (T)this;
    }

    @Override
    public T istore_1() {
        delegate.istore_1();
        return (T)this;
    }

    @Override
    public T istore_2() {
        delegate.istore_2();
        return (T)this;
    }

    @Override
    public T istore_3() {
        delegate.istore_3();
        return (T)this;
    }

    @Override
    public T lstore_0() {
        delegate.lstore_0();
        return (T)this;
    }

    @Override
    public T lstore_1() {
        delegate.lstore_1();
        return (T)this;
    }

    @Override
    public T lstore_2() {
        delegate.lstore_2();
        return (T)this;
    }

    @Override
    public T lstore_3() {
        delegate.lstore_3();
        return (T)this;
    }

    @Override
    public T fstore_0() {
        delegate.fstore_0();
        return (T)this;
    }

    @Override
    public T fstore_1() {
        delegate.fstore_1();
        return (T)this;
    }

    @Override
    public T fstore_2() {
        delegate.fstore_2();
        return (T)this;
    }

    @Override
    public T fstore_3() {
        delegate.fstore_3();
        return (T)this;
    }

    @Override
    public T dstore_0() {
        delegate.dstore_0();
        return (T)this;
    }

    @Override
    public T dstore_1() {
        delegate.dstore_1();
        return (T)this;
    }

    @Override
    public T dstore_2() {
        delegate.dstore_2();
        return (T)this;
    }

    @Override
    public T dstore_3() {
        delegate.dstore_3();
        return (T)this;
    }

    @Override
    public T astore_0() {
        delegate.astore_0();
        return (T)this;
    }

    @Override
    public T astore_1() {
        delegate.astore_1();
        return (T)this;
    }

    @Override
    public T astore_2() {
        delegate.astore_2();
        return (T)this;
    }

    @Override
    public T astore_3() {
        delegate.astore_3();
        return (T)this;
    }

    @Override
    public T iastore() {
        delegate.iastore();
        return (T)this;
    }

    @Override
    public T lastore() {
        delegate.lastore();
        return (T)this;
    }

    @Override
    public T fastore() {
        delegate.fastore();
        return (T)this;
    }

    @Override
    public T dastore() {
        delegate.dastore();
        return (T)this;
    }

    @Override
    public T aastore() {
        delegate.aastore();
        return (T)this;
    }

    @Override
    public T bastore() {
        delegate.bastore();
        return (T)this;
    }

    @Override
    public T castore() {
        delegate.castore();
        return (T)this;
    }

    @Override
    public T sastore() {
        delegate.sastore();
        return (T)this;
    }

    @Override
    public T pop() {
        delegate.pop();
        return (T)this;
    }

    @Override
    public T pop2() {
        delegate.pop2();
        return (T)this;
    }

    @Override
    public T dup() {
        delegate.dup();
        return (T)this;
    }

    @Override
    public T dup_x1() {
        delegate.dup_x1();
        return (T)this;
    }

    @Override
    public T dup_x2() {
        delegate.dup_x2();
        return (T)this;
    }

    @Override
    public T dup2() {
        delegate.dup2();
        return (T)this;
    }

    @Override
    public T dup2_x1() {
        delegate.dup2_x1();
        return (T)this;
    }

    @Override
    public T dup2_x2() {
        delegate.dup2_x2();
        return (T)this;
    }

    @Override
    public T swap() {
        delegate.swap();
        return (T)this;
    }

    @Override
    public T iadd() {
        delegate.iadd();
        return (T)this;
    }

    @Override
    public T ladd() {
        delegate.ladd();
        return (T)this;
    }

    @Override
    public T fadd() {
        delegate.fadd();
        return (T)this;
    }

    @Override
    public T dadd() {
        delegate.dadd();
        return (T)this;
    }

    @Override
    public T isub() {
        delegate.isub();
        return (T)this;
    }

    @Override
    public T lsub() {
        delegate.lsub();
        return (T)this;
    }

    @Override
    public T fsub() {
        delegate.fsub();
        return (T)this;
    }

    @Override
    public T dsub() {
        delegate.dsub();
        return (T)this;
    }

    @Override
    public T imul() {
        delegate.imul();
        return (T)this;
    }

    @Override
    public T lmul() {
        delegate.lmul();
        return (T)this;
    }

    @Override
    public T fmul() {
        delegate.fmul();
        return (T)this;
    }

    @Override
    public T dmul() {
        delegate.dmul();
        return (T)this;
    }

    @Override
    public T idiv() {
        delegate.idiv();
        return (T)this;
    }

    @Override
    public T ldiv() {
        delegate.ldiv();
        return (T)this;
    }

    @Override
    public T fdiv() {
        delegate.fdiv();
        return (T)this;
    }

    @Override
    public T ddiv() {
        delegate.ddiv();
        return (T)this;
    }

    @Override
    public T irem() {
        delegate.irem();
        return (T)this;
    }

    @Override
    public T lrem() {
        delegate.lrem();
        return (T)this;
    }

    @Override
    public T frem() {
        delegate.frem();
        return (T)this;
    }

    @Override
    public T drem() {
        delegate.drem();
        return (T)this;
    }

    @Override
    public T ineg() {
        delegate.ineg();
        return (T)this;
    }

    @Override
    public T lneg() {
        delegate.lneg();
        return (T)this;
    }

    @Override
    public T fneg() {
        delegate.fneg();
        return (T)this;
    }

    @Override
    public T dneg() {
        delegate.dneg();
        return (T)this;
    }

    @Override
    public T ishl() {
        delegate.ishl();
        return (T)this;
    }

    @Override
    public T lshl() {
        delegate.lshl();
        return (T)this;
    }

    @Override
    public T ishr() {
        delegate.ishr();
        return (T)this;
    }

    @Override
    public T lshr() {
        delegate.lshr();
        return (T)this;
    }

    @Override
    public T iushr() {
        delegate.iushr();
        return (T)this;
    }

    @Override
    public T lushr() {
        delegate.lushr();
        return (T)this;
    }

    @Override
    public T iand() {
        delegate.iand();
        return (T)this;
    }

    @Override
    public T land() {
        delegate.land();
        return (T)this;
    }

    @Override
    public T ior() {
        delegate.ior();
        return (T)this;
    }

    @Override
    public T lor() {
        delegate.lor();
        return (T)this;
    }

    @Override
    public T ixor() {
        delegate.ixor();
        return (T)this;
    }

    @Override
    public T lxor() {
        delegate.lxor();
        return (T)this;
    }

    @Override
    public T iinc(int variableIndex, int constant) {
        delegate.iinc(variableIndex, constant);
        return (T)this;
    }

    @Override
    public T i2l() {
        delegate.i2l();
        return (T)this;
    }

    @Override
    public T i2f() {
        delegate.i2f();
        return (T)this;
    }

    @Override
    public T i2d() {
        delegate.i2d();
        return (T)this;
    }

    @Override
    public T l2i() {
        delegate.l2i();
        return (T)this;
    }

    @Override
    public T l2f() {
        delegate.l2f();
        return (T)this;
    }

    @Override
    public T l2d() {
        delegate.l2d();
        return (T)this;
    }

    @Override
    public T f2i() {
        delegate.f2i();
        return (T)this;
    }

    @Override
    public T f2l() {
        delegate.f2l();
        return (T)this;
    }

    @Override
    public T f2d() {
        delegate.f2d();
        return (T)this;
    }

    @Override
    public T d2i() {
        delegate.d2i();
        return (T)this;
    }

    @Override
    public T d2l() {
        delegate.d2l();
        return (T)this;
    }

    @Override
    public T d2f() {
        delegate.d2f();
        return (T)this;
    }

    @Override
    public T i2b() {
        delegate.i2b();
        return (T)this;
    }

    @Override
    public T i2c() {
        delegate.i2c();
        return (T)this;
    }

    @Override
    public T i2s() {
        delegate.i2s();
        return (T)this;
    }

    @Override
    public T lcmp() {
        delegate.lcmp();
        return (T)this;
    }

    @Override
    public T fcmpl() {
        delegate.fcmpl();
        return (T)this;
    }

    @Override
    public T fcmpg() {
        delegate.fcmpg();
        return (T)this;
    }

    @Override
    public T dcmpl() {
        delegate.dcmpl();
        return (T)this;
    }

    @Override
    public T dcmpg() {
        delegate.dcmpg();
        return (T)this;
    }

    @Override
    public T ifeq(Label branchLabel) {
        delegate.ifeq(branchLabel);
        return (T)this;
    }

    @Override
    public T ifne(Label branchLabel) {
        delegate.ifne(branchLabel);
        return (T)this;
    }

    @Override
    public T iflt(Label branchLabel) {
        delegate.iflt(branchLabel);
        return (T)this;
    }

    @Override
    public T ifge(Label branchLabel) {
        delegate.ifge(branchLabel);
        return (T)this;
    }

    @Override
    public T ifgt(Label branchLabel) {
        delegate.ifgt(branchLabel);
        return (T)this;
    }

    @Override
    public T ifle(Label branchLabel) {
        delegate.ifle(branchLabel);
        return (T)this;
    }

    @Override
    public T ificmpeq(Label branchLabel) {
        delegate.ificmpeq(branchLabel);
        return (T)this;
    }

    @Override
    public T ificmpne(Label branchLabel) {
        delegate.ificmpne(branchLabel);
        return (T)this;
    }

    @Override
    public T ificmplt(Label branchLabel) {
        delegate.ificmplt(branchLabel);
        return (T)this;
    }

    @Override
    public T ificmpge(Label branchLabel) {
        delegate.ificmpge(branchLabel);
        return (T)this;
    }

    @Override
    public T ificmpgt(Label branchLabel) {
        delegate.ificmpgt(branchLabel);
        return (T)this;
    }

    @Override
    public T ificmple(Label branchLabel) {
        delegate.ificmple(branchLabel);
        return (T)this;
    }

    @Override
    public T ifacmpeq(Label branchLabel) {
        delegate.ifacmpeq(branchLabel);
        return (T)this;
    }

    @Override
    public T ifacmpne(Label branchLabel) {
        delegate.ifacmpne(branchLabel);
        return (T)this;
    }

    @Override
    public T goto_(Label branchLabel) {
        delegate.goto_(branchLabel);
        return (T)this;
    }

    @Override
    public T jsr(Label branchLabel) {
        delegate.jsr(branchLabel);
        return (T)this;
    }

    @Override
    public T ret(int variableIndex) {
        delegate.ret(variableIndex);
        return (T)this;
    }

    @Override
    public T tableswitch(Label defaultLabel, int lowCase, int highCase, Label[] jumpLabels) {
        delegate.tableswitch(defaultLabel, lowCase, highCase, jumpLabels);
        return (T)this;
    }

    @Override
    public T lookupswitch(Label defaultLabel, int[] cases, Label[] jumpLabels) {
        delegate.lookupswitch(defaultLabel, cases, jumpLabels);
        return (T)this;
    }

    @Override
    public T ireturn() {
        delegate.ireturn();
        return (T)this;
    }

    @Override
    public T lreturn() {
        delegate.lreturn();
        return (T)this;
    }

    @Override
    public T freturn() {
        delegate.freturn();
        return (T)this;
    }

    @Override
    public T dreturn() {
        delegate.dreturn();
        return (T)this;
    }

    @Override
    public T areturn() {
        delegate.areturn();
        return (T)this;
    }

    @Override
    public T return_() {
        delegate.return_();
        return (T)this;
    }

    @Override
    public T getstatic(Clazz clazz, Field field) {
        delegate.getstatic(clazz, field);
        return (T)this;
    }

    @Override
    public T getstatic(String className, String name, String descriptor) {
        delegate.getstatic(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T getstatic(String className, String name, String descriptor, Clazz referencedClass, Field referencedField) {
        delegate.getstatic(className, name, descriptor, referencedClass, referencedField);
        return (T)this;
    }

    @Override
    public T getstatic(int constantIndex) {
        delegate.getstatic(constantIndex);
        return (T)this;
    }

    @Override
    public T putstatic(Clazz referencedClass, Field referencedField) {
        delegate.putstatic(referencedClass, referencedField);
        return (T)this;
    }

    @Override
    public T putstatic(String className, String name, String descriptor) {
        delegate.putstatic(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T putstatic(String className, String name, String descriptor, Clazz referencedClass, Field referencedField) {
        delegate.putstatic(className, name, descriptor, referencedClass, referencedField);
        return (T)this;
    }

    @Override
    public T putstatic(int constantIndex) {
        delegate.putstatic(constantIndex);
        return (T)this;
    }

    @Override
    public T getfield(Clazz clazz, Field field) {
        delegate.getfield(clazz, field);
        return (T)this;
    }

    @Override
    public T getfield(String className, String name, String descriptor) {
        delegate.getfield(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T getfield(String className, String name, String descriptor, Clazz referencedClass, Field referencedField) {
        delegate.getfield(className, name, descriptor, referencedClass, referencedField);
        return (T)this;
    }

    @Override
    public T getfield(int constantIndex) {
        delegate.getfield(constantIndex);
        return (T)this;
    }

    @Override
    public T putfield(Clazz clazz, Field field) {
        delegate.putfield(clazz, field);
        return (T)this;
    }

    @Override
    public T putfield(String className, String name, String descriptor) {
        delegate.putfield(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T putfield(String className, String name, String descriptor, Clazz referencedClass, Field referencedField) {
        delegate.putfield(className, name, descriptor, referencedClass, referencedField);
        return (T)this;
    }

    @Override
    public T putfield(int constantIndex) {
        delegate.putfield(constantIndex);
        return (T)this;
    }

    @Override
    public T invokevirtual(Clazz clazz, Method method) {
        delegate.invokevirtual(clazz, method);
        return (T)this;
    }

    @Override
    public T invokevirtual(String className, String name, String descriptor) {
        delegate.invokevirtual(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T invokevirtual(String className, String name, String descriptor, Clazz referencedClass, Method referencedMethod) {
        delegate.invokevirtual(className, name, descriptor, referencedClass, referencedMethod);
        return (T)this;
    }

    @Override
    public T invokevirtual(int constantIndex) {
        delegate.invokevirtual(constantIndex);
        return (T)this;
    }

    @Override
    public T invokespecial(Clazz clazz, Method method) {
        delegate.invokespecial(clazz, method);
        return (T)this;
    }

    @Override
    public T invokespecial(String className, String name, String descriptor) {
        delegate.invokespecial(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T invokespecial(String className, String name, String descriptor, Clazz referencedClass, Method referencedMethod) {
        delegate.invokespecial(className, name, descriptor, referencedClass, referencedMethod);
        return (T)this;
    }

    @Override
    public T invokespecial(int constantIndex) {
        delegate.invokespecial(constantIndex);
        return (T)this;
    }

    @Override
    public T invokestatic(Clazz clazz, Method method) {
        delegate.invokestatic(clazz, method);
        return (T)this;
    }

    @Override
    public T invokestatic(String className, String name, String descriptor) {
        delegate.invokestatic(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T invokestatic(String className, String name, String descriptor, Clazz referencedClass, Method referencedMethod) {
        delegate.invokestatic(className, name, descriptor, referencedClass, referencedMethod);
        return (T)this;
    }

    @Override
    public T invokestatic_interface(Clazz clazz, Method method) {
        delegate.invokestatic_interface(clazz, method);
        return (T)this;
    }

    @Override
    public T invokestatic_interface(String className, String name, String descriptor) {
        delegate.invokestatic_interface(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T invokestatic_interface(String className, String name, String descriptor, Clazz referencedClass, Method referencedMethod) {
        delegate.invokestatic_interface(className, name, descriptor, referencedClass, referencedMethod);
        return (T)this;
    }

    @Override
    public T invokestatic(int constantIndex) {
        delegate.invokestatic(constantIndex);
        return (T)this;
    }

    @Override
    public T invokeinterface(Clazz clazz, Method method) {
        delegate.invokeinterface(clazz, method);
        return (T)this;
    }

    @Override
    public T invokeinterface(String className, String name, String descriptor) {
        delegate.invokeinterface(className, name, descriptor);
        return (T)this;
    }

    @Override
    public T invokeinterface(String className, String name, String descriptor, Clazz referencedClass, Method referencedMethod) {
        delegate.invokeinterface(className, name, descriptor, referencedClass, referencedMethod);
        return (T)this;
    }

    @Override
    public T invokeinterface(int constantIndex, int constant) {
        delegate.invokeinterface(constantIndex, constant);
        return (T)this;
    }

    @Override
    public T invokedynamic(int bootStrapMethodIndex, String name, String descriptor, Clazz[] referencedClasses) {
        delegate.invokedynamic(bootStrapMethodIndex, name, descriptor, referencedClasses);
        return (T)this;
    }

    @Override
    public T invokedynamic(int constantIndex) {
        delegate.invokedynamic(constantIndex);
        return (T)this;
    }

    @Override
    public T new_(Clazz clazz) {
        delegate.new_(clazz);
        return (T)this;
    }

    @Override
    public T new_(String className) {
        delegate.new_(className);
        return (T)this;
    }

    @Override
    public T new_(String className, Clazz referencedClass) {
        delegate.new_(className, referencedClass);
        return (T)this;
    }

    @Override
    public T new_(int constantIndex) {
        delegate.new_(constantIndex);
        return (T)this;
    }

    @Override
    public T newarray(int constant) {
        delegate.newarray(constant);
        return (T)this;
    }

    @Override
    public T anewarray(String className, Clazz referencedClass) {
        delegate.anewarray(className, referencedClass);
        return (T)this;
    }

    @Override
    public T anewarray(int constantIndex) {
        delegate.anewarray(constantIndex);
        return (T)this;
    }

    @Override
    public T arraylength() {
        delegate.arraylength();
        return (T)this;
    }

    @Override
    public T athrow() {
        delegate.athrow();
        return (T)this;
    }

    @Override
    public T checkcast(String className) {
        delegate.checkcast(className);
        return (T)this;
    }

    @Override
    public T checkcast(String className, Clazz referencedClass) {
        delegate.checkcast(className, referencedClass);
        return (T)this;
    }

    @Override
    public T checkcast(int constantIndex) {
        delegate.checkcast(constantIndex);
        return (T)this;
    }

    @Override
    public T instanceof_(String className, Clazz referencedClass) {
        delegate.instanceof_(className, referencedClass);
        return (T)this;
    }

    @Override
    public T instanceof_(int constantIndex) {
        delegate.instanceof_(constantIndex);
        return (T)this;
    }

    @Override
    public T monitorenter() {
        delegate.monitorenter();
        return (T)this;
    }

    @Override
    public T monitorexit() {
        delegate.monitorexit();
        return (T)this;
    }

    @Override
    public T wide() {
        delegate.wide();
        return (T)this;
    }

    @Override
    public T multianewarray(String className, Clazz referencedClass, int dimensions) {
        delegate.multianewarray(className, referencedClass, dimensions);
        return (T)this;
    }

    @Override
    public T multianewarray(int constantIndex, int dimensions) {
        delegate.multianewarray(constantIndex, dimensions);
        return (T)this;
    }

    @Override
    public T ifnull(Label branchLabel) {
        delegate.ifnull(branchLabel);
        return (T)this;
    }

    @Override
    public T ifnonnull(Label branchLabel) {
        delegate.ifnonnull(branchLabel);
        return (T)this;
    }

    @Override
    public T goto_w(Label branchLabel) {
        delegate.goto_w(branchLabel);
        return (T)this;
    }

    @Override
    public T jsr_w(Label branchLabel) {
        delegate.jsr_w(branchLabel);
        return (T)this;
    }

    @Override
    public T pushPrimitive(Object primitive, char internalType) {
        delegate.pushPrimitive(primitive, internalType);
        return (T)this;
    }

    @Override
    public T pushInt(int value) {
        delegate.pushInt(value);
        return (T)this;
    }

    @Override
    public T pushFloat(float value) {
        delegate.pushFloat(value);
        return (T)this;
    }

    @Override
    public T pushLong(long value) {
        delegate.pushLong(value);
        return (T)this;
    }

    @Override
    public T pushDouble(double value) {
        delegate.pushDouble(value);
        return (T)this;
    }

    @Override
    public T pushNewArray(String elementTypeOrClassName, int size) {
        delegate.pushNewArray(elementTypeOrClassName, size);
        return (T)this;
    }

    @Override
    public T load(int variableIndex, String internalType) {
        delegate.load(variableIndex, internalType);
        return (T)this;
    }

    @Override
    public T load(int variableIndex, char internalType) {
        delegate.load(variableIndex, internalType);
        return (T)this;
    }

    @Override
    public T store(int variableIndex, String internalType) {
        delegate.store(variableIndex, internalType);
        return (T)this;
    }

    @Override
    public T store(int variableIndex, char internalType) {
        delegate.store(variableIndex, internalType);
        return (T)this;
    }

    @Override
    public T storeToArray(String elementType) {
        delegate.storeToArray(elementType);
        return (T)this;
    }

    @Override
    public T return_(String internalType) {
        delegate.return_(internalType);
        return (T)this;
    }

    @Override
    public T appendPrintIntegerInstructions(String message) {
        delegate.appendPrintIntegerInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintIntegerHexInstructions(String message) {
        delegate.appendPrintIntegerHexInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintLongInstructions(String message) {
        delegate.appendPrintLongInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintStringInstructions(String message) {
        delegate.appendPrintStringInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintObjectInstructions(String message) {
        delegate.appendPrintObjectInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintStackTraceInstructions(String message) {
        delegate.appendPrintStackTraceInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintInstructions(String message) {
        delegate.appendPrintInstructions(message);
        return (T)this;
    }

    @Override
    public T appendPrintIntegerInstructions() {
        delegate.appendPrintIntegerInstructions();
        return (T)this;
    }

    @Override
    public T appendPrintIntegerHexInstructions() {
        delegate.appendPrintIntegerHexInstructions();
        return (T)this;
    }

    @Override
    public T appendPrintLongInstructions() {
        delegate.appendPrintLongInstructions();
        return (T)this;
    }

    @Override
    public T appendPrintStringInstructions() {
        delegate.appendPrintStringInstructions();
        return (T)this;
    }

    @Override
    public T appendPrintObjectInstructions() {
        delegate.appendPrintObjectInstructions();
        return (T)this;
    }

    @Override
    public T appendPrintStackTraceInstructions() {
        delegate.appendPrintStackTraceInstructions();
        return (T)this;
    }

    @Override
    public void addCodeAttribute(ProgramClass programClass, ProgramMethod programMethod) {
        delegate.addCodeAttribute(programClass, programMethod);
    }

    @Override
    public void visitAnyAttribute(Clazz clazz, Attribute attribute) {
        delegate.visitAnyAttribute(clazz, attribute);
    }

    @Override
    public void visitCodeAttribute(Clazz clazz, Method method, CodeAttribute codeAttribute) {
        delegate.visitCodeAttribute(clazz, method, codeAttribute);
    }
}
