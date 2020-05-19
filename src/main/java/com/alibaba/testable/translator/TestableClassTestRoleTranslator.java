package com.alibaba.testable.translator;

import com.alibaba.testable.model.TestLibType;
import com.alibaba.testable.model.TestableContext;
import com.alibaba.testable.translator.tree.TestableFieldAccess;
import com.alibaba.testable.translator.tree.TestableMethodInvocation;
import com.alibaba.testable.util.ConstPool;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.tree.JCTree.*;

import java.lang.reflect.Modifier;

/**
 * Travel AST
 *
 * @author flin
 */
public class TestableClassTestRoleTranslator extends TreeTranslator {

    private static final String ANNOTATION_TESTABLE_INJECT = "com.alibaba.testable.annotation.TestableInject";
    private static final String ANNOTATION_JUNIT5_SETUP = "org.junit.jupiter.api.BeforeEach";
    private static final String ANNOTATION_JUNIT5_TEST = "org.junit.jupiter.api.Test";
    private final TestableContext cx;
    private String sourceClassName = "";
    private ListBuffer<Name> sourceClassIns = new ListBuffer();
    private List<String> stubbornFields = List.nil();

    /**
     * MethodName -> (ResultType -> ParameterTypes)
     */
    private ListBuffer<Pair<Name, Pair<JCExpression, List<JCExpression>>>> injectMethods = new ListBuffer<>();
    private String testSetupMethodName = "";
    private TestLibType testLibType = TestLibType.JUnit4;

    public TestableClassTestRoleTranslator(String pkgName, String className, TestableContext cx) {
        this.sourceClassName = className;
        this.cx = cx;
        try {
            stubbornFields = List.from(
                (String[])Class.forName(pkgName + "." + className + ConstPool.TESTABLE)
                .getMethod(ConstPool.STUBBORN_FIELD_METHOD)
                .invoke(null));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void visitVarDef(JCVariableDecl jcVariableDecl) {
        super.visitVarDef(jcVariableDecl);
        if (((JCIdent)jcVariableDecl.vartype).name.toString().equals(sourceClassName)) {
            jcVariableDecl.vartype = getTestableClassIdent(jcVariableDecl.vartype);
            sourceClassIns.add(jcVariableDecl.name);
        }
    }

    @Override
    public void visitNewClass(JCNewClass jcNewClass) {
        super.visitNewClass(jcNewClass);
        if (getSimpleClassName(jcNewClass).equals(sourceClassName)) {
            jcNewClass.clazz = getTestableClassIdent(jcNewClass.clazz);
        }
    }

    private String getSimpleClassName(JCNewClass jcNewClass) {
        if (jcNewClass.clazz.getClass().equals(JCIdent.class)) {
            return ((JCIdent)jcNewClass.clazz).name.toString();
        } else if (jcNewClass.clazz.getClass().equals(JCFieldAccess.class)) {
            return ((JCFieldAccess)jcNewClass.clazz).name.toString();
        } else {
            return "";
        }
    }

    @Override
    public void visitExec(JCExpressionStatement jcExpressionStatement) {
        if (jcExpressionStatement.expr.getClass().equals(JCAssign.class) &&
            isAssignStubbornField((JCAssign)jcExpressionStatement.expr)) {
            JCAssign assign = (JCAssign)jcExpressionStatement.expr;
            // TODO: Use treeMaker.Apply() and treeMaker.Select()
            TestableFieldAccess stubbornSetter = new TestableFieldAccess(((JCFieldAccess)assign.lhs).selected,
                getStubbornSetterMethodName(assign), null);
            jcExpressionStatement.expr = new TestableMethodInvocation(null, stubbornSetter,
                com.sun.tools.javac.util.List.of(assign.rhs));
        }
        super.visitExec(jcExpressionStatement);
    }

    @Override
    public void visitMethodDef(JCMethodDecl jcMethodDecl) {
        for (JCAnnotation a : jcMethodDecl.mods.annotations) {
            switch (a.type.tsym.toString()) {
                case ANNOTATION_TESTABLE_INJECT:
                    ListBuffer<JCExpression> args = new ListBuffer<>();
                    for (JCVariableDecl p : jcMethodDecl.params) {
                        args.add(cx.treeMaker.Select(p.vartype, cx.names.fromString("class")));
                    }
                    JCExpression retType = cx.treeMaker.Select(jcMethodDecl.restype, cx.names.fromString("class"));
                    injectMethods.add(Pair.of(jcMethodDecl.name, Pair.of(retType, args.toList())));
                    break;
                case ANNOTATION_JUNIT5_SETUP:
                    testSetupMethodName = jcMethodDecl.name.toString();
                    jcMethodDecl.mods.annotations = removeAnnotation(jcMethodDecl.mods.annotations, ANNOTATION_JUNIT5_SETUP);
                    break;
                case ANNOTATION_JUNIT5_TEST:
                    testLibType = TestLibType.JUnit5;
                    break;
                default:
            }
        }
        super.visitMethodDef(jcMethodDecl);
    }

    @Override
    public void visitClassDef(JCClassDecl jcClassDecl) {
        super.visitClassDef(jcClassDecl);
        ListBuffer<JCTree> ndefs = new ListBuffer<>();
        ndefs.addAll(jcClassDecl.defs);
        JCModifiers mods = cx.treeMaker.Modifiers(Modifier.PUBLIC, makeAnnotations(ANNOTATION_JUNIT5_SETUP));
        ndefs.add(cx.treeMaker.MethodDef(mods, cx.names.fromString("testableSetup"),
            cx.treeMaker.Type(new Type.JCVoidType()), List.<JCTypeParameter>nil(),
            List.<JCVariableDecl>nil(), List.<JCExpression>nil(), testableSetupBlock(), null));
        jcClassDecl.defs = ndefs.toList();
    }

    /**
     * For break point
     */
    @Override
    public void visitAssign(JCAssign jcAssign) {
        super.visitAssign(jcAssign);
    }

    /**
     * For break point
     */
    @Override
    public void visitSelect(JCFieldAccess jcFieldAccess) {
        super.visitSelect(jcFieldAccess);
    }

    private List<JCAnnotation> makeAnnotations(String fullAnnotationName) {
        JCExpression setupAnnotation = nameToExpression(fullAnnotationName);
        return List.of(cx.treeMaker.Annotation(setupAnnotation, List.<JCExpression>nil()));
    }

    private JCExpression nameToExpression(String dotName) {
        String[] nameParts = dotName.split("\\.");
        JCExpression e = cx.treeMaker.Ident(cx.names.fromString(nameParts[0]));
        for (int i = 1 ; i < nameParts.length ; i++) {
            e = cx.treeMaker.Select(e, cx.names.fromString(nameParts[i]));
        }
        return e;
    }

    private JCBlock testableSetupBlock() {
        ListBuffer<JCStatement> statements = new ListBuffer<>();
        for (Pair<Name, Pair<JCExpression, List<JCExpression>>> m : injectMethods.toList()) {
            JCExpression key = nameToExpression("n.e.k");
            JCExpression classType = m.snd.fst;
            JCExpression parameterTypes = cx.treeMaker.NewArray(cx.treeMaker.Ident(cx.names.fromString("Class")),
                List.<JCExpression>nil(), m.snd.snd);
            JCNewClass keyClass = cx.treeMaker.NewClass(null, List.<JCExpression>nil(), key,
                List.of(classType, parameterTypes), null);
            JCExpression value = nameToExpression("n.e.v");
            JCExpression thisIns = cx.treeMaker.Ident(cx.names.fromString("this"));
            JCExpression methodName = cx.treeMaker.Literal(m.fst.toString());
            JCNewClass valClass = cx.treeMaker.NewClass(null, List.<JCExpression>nil(), value,
                List.of(thisIns, methodName), null);
            JCExpression addInjectMethod = nameToExpression("n.e.a");
            JCMethodInvocation apply = cx.treeMaker.Apply(List.<JCExpression>nil(), addInjectMethod,
                List.from(new JCExpression[] {keyClass, valClass}));
            statements.append(cx.treeMaker.Exec(apply));
        }
        if (!testSetupMethodName.isEmpty()) {
            statements.append(cx.treeMaker.Exec(cx.treeMaker.Apply(List.<JCExpression>nil(),
                nameToExpression(testSetupMethodName), List.<JCExpression>nil())));
        }
        return cx.treeMaker.Block(0, statements.toList());
    }

    private List<JCAnnotation> removeAnnotation(List<JCAnnotation> annotations, String target) {
        ListBuffer<JCAnnotation> nb = new ListBuffer<>();
        for (JCAnnotation i : annotations) {
            if (!i.type.tsym.toString().equals(target)) {
                nb.add(i);
            }
        }
        return nb.toList();
    }

    private Name getStubbornSetterMethodName(JCAssign assign) {
        String name = ((JCFieldAccess)assign.lhs).name.toString() + ConstPool.TESTABLE_SET_METHOD_PREFIX;
        return cx.names.fromString(name);
    }

    private boolean isAssignStubbornField(JCAssign expr) {
        return expr.lhs.getClass().equals(JCFieldAccess.class) &&
            sourceClassIns.contains(((JCIdent)((JCFieldAccess)(expr).lhs).selected).name) &&
            stubbornFields.contains(((JCFieldAccess)(expr).lhs).name.toString());
    }

    private JCIdent getTestableClassIdent(JCExpression clazz) {
        Name className = ((JCIdent)clazz).name;
        return cx.treeMaker.Ident(cx.names.fromString(className + ConstPool.TESTABLE));
    }

}
