package fr.umlv.smalljs.astinterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.ast.Visitor;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public class ASTInterpreter {
    private static <T> T as(Object value, Class<T> type, Expr failedExpr) {
        try {
            return type.cast(value);
        } catch(@SuppressWarnings("unused") ClassCastException e) {
            throw new Failure("at line " + failedExpr.lineNumber() + ", type error " + value + " is not a " + type.getSimpleName());
        }
    }

    static Object visit(Expr expr, JSObject env) {
        return VISITOR.visit(expr, env);
    }

    private static final Visitor<JSObject, Object> VISITOR =
            new Visitor<JSObject, Object>()
                    .when(Block.class, (block, env) -> {
                        for (var intr: block.instrs()) {
                            //we can use the same env because in this language we admit that we have juste VAR (no let)
                            visit(intr, env);
                        }
                        return UNDEFINED;
                    })
                    .when(Literal.class, (literal, env) -> {
                        return literal.value();
                    })
                    
                    .when(FunCall.class, (funCall, env) -> {
                        var value = visit(funCall.qualifier(), env);
                        var function = as(value, JSObject.class, funCall);
                        var arguments = funCall.args().stream().map(expr -> visit(expr, env)).toArray();
                        return function.invoke(UNDEFINED, arguments);
                    })
                    .when(LocalVarAccess.class, (localVarAccess, env) -> {
                        var value = env.lookup(localVarAccess.name());
                        return value;
                    })
                    .when(LocalVarAssignment.class, (localVarAssignment, env) -> {
                        var value = env.lookup(localVarAssignment.name());
                        if (!localVarAssignment.declaration() && value == UNDEFINED) {
                            throw new Failure("No variable " + localVarAssignment.name() + " defined");
                        }
                        if (localVarAssignment.declaration() && value != UNDEFINED) {
                            throw new Failure("Variable " + localVarAssignment.name() + " already defined");
                        }
                        var result = visit(localVarAssignment.expr(), env);
                        env.register(localVarAssignment.name(), result);
                        return UNDEFINED;
                    })
                    .when(Fun.class, (fun, env) -> {
                        var funcName = fun.name().orElse("Lambda");
                        JSObject.Invoker invoker = new JSObject.Invoker() {
                            @Override
                            public Object invoke(JSObject self, Object receiver, Object... args) {
                                if (fun.parameters().size() != args.length) throw new Failure("wrong number of arguments at " + fun.lineNumber());
                                var newEnv = JSObject.newEnv(env);
                                newEnv.register("this", receiver);
                                for (int i = 0; i < fun.parameters().size(); i++) {
                                    newEnv.register(fun.parameters().get(i), args[i]);
                                }
                                try {
                                    return visit(fun.body(), newEnv);
                                } catch (ReturnError error) {
                                    return error.getValue();
                                }
                            }
                        };
                        var func = JSObject.newFunction(funcName, invoker);
                        fun.name().ifPresent(name -> env.register(name, func));
                        return func;
                    })
                    .when(Return.class, (_return, env) -> {
                        var value = visit(_return.expr(), env);
                        throw new ReturnError(value);
                    })
                    .when(If.class, (_if, env) -> {
                        var condition = visit(_if.condition(), env);
                        if (condition.equals(0)) {
                            return visit(_if.falseBlock(), env);
                        } else {
                           return visit(_if.trueBlock(), env);
                        }
                    })
                    .when(New.class, (_new, env) -> {
                        var object = JSObject.newObject(null);
                        _new.initMap().forEach(
                                (property, init) -> {
                                   var value = visit(init, env);
                                   object.register(property, value);
                                }
                        );
                       return object;
                    })
                    .when(FieldAccess.class, (fieldAccess, env) -> {
                        var field = as(visit(fieldAccess.receiver(), env), JSObject.class, fieldAccess);
                        return field.lookup(fieldAccess.name());
                    })
                    .when(FieldAssignment.class, (fieldAssignment, env) -> {
                        var field = as(visit(fieldAssignment.receiver(), env), JSObject.class, fieldAssignment);
                        field.register(fieldAssignment.name(), visit(fieldAssignment.expr(), env));
                        return UNDEFINED;
                    })
                    .when(MethodCall.class, (methodCall, env) -> {
                        var value = as(visit(methodCall.receiver(), env), JSObject.class, methodCall);
                        var method = as(value.lookup(methodCall.name()), JSObject.class, methodCall);
                        return method.invoke(value, methodCall.args().stream().map(expr -> visit(expr, env)).toArray());
                    })
            ;

    @SuppressWarnings("unchecked")
    public static void interpret(Script script, PrintStream outStream) {
        JSObject globalEnv = JSObject.newEnv(null);
        Block body = script.body();
        globalEnv.register("global", globalEnv);
        globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
            System.err.println("print called with " + Arrays.toString(args));
            outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
            return UNDEFINED;
        }));
        globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
        globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
        globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
        globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
        globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));

        globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
        globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
        globalEnv.register("<", JSObject.newFunction("<",   (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
        globalEnv.register("<=", JSObject.newFunction("<=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
        globalEnv.register(">", JSObject.newFunction(">",   (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
        globalEnv.register(">=", JSObject.newFunction(">=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
        visit(body, globalEnv);
    }
}

