package com.trigersoft.jaque.expression;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import com.trigersoft.jaque.expression.invoke.SerializedLambda;

public class ExpressionClassCracker {

	private static final String DUMP_FOLDER_SYSTEM_PROPERTY = "jdk.internal.lambda.dumpProxyClasses";
	private static final URLClassLoader lambdaClassLoader;
	private static final String lambdaClassLoaderCreationError;

	static {
		String folderPath = System.getProperty(DUMP_FOLDER_SYSTEM_PROPERTY);
		if (folderPath == null) {
			lambdaClassLoaderCreationError = "Ensure that the '"
					+ DUMP_FOLDER_SYSTEM_PROPERTY
					+ "' system property is properly set.";
			lambdaClassLoader = null;
		} else {
			File folder = new File(folderPath);
			if (!folder.isDirectory()) {
				lambdaClassLoaderCreationError = "Ensure that the '"
						+ DUMP_FOLDER_SYSTEM_PROPERTY
						+ "' system property is properly set (" + folderPath
						+ " does not exist).";
				lambdaClassLoader = null;
			} else {
				URL folderURL;
				try {
					folderURL = folder.toURI().toURL();
				} catch (MalformedURLException mue) {
					throw new RuntimeException(mue);
				}

				lambdaClassLoaderCreationError = null;
				lambdaClassLoader = new URLClassLoader(new URL[] { folderURL });
			}
		}
	}

	LambdaExpression<?> lambda(Object lambda) {
		Class<?> lambdaClass = lambda.getClass();
		if (!lambdaClass.isSynthetic())
			throw new IllegalArgumentException(
					"The requested object is not a Java lambda");

		ExpressionClassVisitor lambdaVisitor;
		if (lambda instanceof Serializable) {
			SerializedLambda extracted = SerializedLambda
					.extractLambda((Serializable) lambda);
			lambdaVisitor = (extracted.capturedArgs != null && extracted.capturedArgs.length > 0) ? parseFromFileSystem(
					lambda, lambdaClass) : parseClass(
					lambdaClass.getClassLoader(),
					classFilePath(extracted.implClass), lambda,
					extracted.implMethodName, extracted.implMethodSignature);
		} else {
			lambdaVisitor = parseFromFileSystem(lambda, lambdaClass);
		}

		Expression lambdaExpression = lambdaVisitor.getResult();
		Class<?> lambdaType = lambdaVisitor.getType();
		ParameterExpression[] lambdaParams = lambdaVisitor.getParams();

		if (!(lambdaExpression instanceof InvocationExpression))
			return Expression.lambda(lambdaType, lambdaExpression,
					Collections.unmodifiableList(Arrays.asList(lambdaParams)));

		InvocationExpression target = (InvocationExpression) stripConvertExpressions(lambdaExpression);
		Method actualMethod = (Method) ((MemberExpression) target.getTarget())
				.getMember();

		// short-circuit method references
		if (!actualMethod.isSynthetic()) {
			return Expression.lambda(lambdaType, target,
					Collections.unmodifiableList(Arrays.asList(lambdaParams)));
		}

		// TODO: in fact must recursively parse all the synthetic methods,
		// so must have a relevant visitor. and then another visitor to reduce
		// forwarded calls

		Class<?> actualClass = actualMethod.getDeclaringClass();
		ClassLoader actualClassLoader = actualClass.getClassLoader();
		String actualClassPath = classFilePath(actualClass.getName());
		ExpressionClassVisitor actualVisitor = parseClass(actualClassLoader,
				actualClassPath, lambda, actualMethod);

		Expression actualExpression = TypeConverter.convert(
				actualVisitor.getResult(), actualVisitor.getType());
		ParameterExpression[] actualParams = actualVisitor.getParams();

		return buildExpression(lambdaType, lambdaParams, target, actualVisitor,
				actualExpression, actualParams);
	}

	private ExpressionClassVisitor parseFromFileSystem(Object lambda,
			Class<?> lambdaClass) {
		if (lambdaClassLoader == null)
			throw new RuntimeException(lambdaClassLoaderCreationError);
		ExpressionClassVisitor lambdaVisitor;
		String lambdaClassPath = lambdaClassFilePath(lambdaClass);
		Method lambdaMethod = findFunctionalMethod(lambdaClass);
		lambdaVisitor = parseClass(lambdaClassLoader, lambdaClassPath, lambda,
				lambdaMethod);
		return lambdaVisitor;
	}

	private String lambdaClassFilePath(Class<?> lambdaClass) {
		String lambdaClassName = lambdaClass.getName();
		String className = lambdaClassName.substring(0,
				lambdaClassName.lastIndexOf('/'));
		return classFilePath(className);
	}

	private String classFilePath(String className) {
		return className.replace('.', '/') + ".class";
	}

	private Method findFunctionalMethod(Class<?> functionalClass) {
		for (Method m : functionalClass.getMethods()) {
			if (!m.isDefault()) {
				return m;
			}
		}
		throw new IllegalArgumentException(
				"Not a lambda expression. No non-default method.");
	}

	private ExpressionClassVisitor parseClass(ClassLoader classLoader,
			String classFilePath, Object lambda, Method method) {
		return parseClass(classLoader, classFilePath, lambda, method.getName(),
				Type.getMethodDescriptor(method));
	}

	private ExpressionClassVisitor parseClass(ClassLoader classLoader,
			String classFilePath, Object lambda, String method,
			String methodDescriptor) {
		ExpressionClassVisitor visitor = new ExpressionClassVisitor(lambda,
				method, methodDescriptor);
		try {
			try (InputStream classStream = getResourceAsStream(classLoader,
					classFilePath)) {
				ClassReader reader = new ClassReader(classStream);
				reader.accept(visitor, ClassReader.SKIP_DEBUG
						| ClassReader.SKIP_FRAMES);
				return visitor;
			}
		} catch (IOException e) {
			throw new RuntimeException("error parsing class file "
					+ classFilePath, e);
		}
	}

	private InputStream getResourceAsStream(ClassLoader classLoader, String path)
			throws FileNotFoundException {
		InputStream stream = classLoader.getResourceAsStream(path);
		if (stream == null)
			throw new FileNotFoundException(path);
		return stream;
	}

	private Expression stripConvertExpressions(Expression expression) {
		while (expression.getExpressionType() == ExpressionType.Convert) {
			expression = ((UnaryExpression) expression).getFirst();
		}
		return expression;
	}

	private LambdaExpression<?> buildExpression(Class<?> lambdaType,
			ParameterExpression[] lambdaParams, InvocationExpression target,
			ExpressionClassVisitor actualVisitor, Expression actualExpression,
			ParameterExpression[] actualParams) {
		// try reduce
		List<Expression> ntArgs = target.getArguments();
		// 1. there must be enough params
		if (ntArgs.size() <= actualParams.length) {
			// 2. newTarget must have all args as PE
			if (allArgumentsAreParameters(ntArgs)) {
				List<ParameterExpression> newInnerParams = new ArrayList<>();

				for (ParameterExpression actualParam : actualParams) {
					ParameterExpression newInnerParam = (ParameterExpression) ntArgs
							.get(actualParam.getIndex());
					newInnerParams.add(newInnerParam);
				}

				Expression reducedExpression = TypeConverter.convert(
						actualExpression, lambdaType);

				return Expression.lambda(lambdaType, reducedExpression,
						Collections.unmodifiableList(newInnerParams));
			}
		}

		LambdaExpression<?> inner = Expression.lambda(actualVisitor.getType(),
				actualExpression,
				Collections.unmodifiableList(Arrays.asList(actualParams)));

		InvocationExpression newTarget = Expression.invoke(inner,
				target.getArguments());

		return Expression.lambda(lambdaType, newTarget,
				Collections.unmodifiableList(Arrays.asList(lambdaParams)));
	}

	private boolean allArgumentsAreParameters(List<Expression> ntArgs) {
		for (Expression e : ntArgs) {
			if (e.getExpressionType() != ExpressionType.Parameter)
				return false;
		}
		return true;
	}

}
