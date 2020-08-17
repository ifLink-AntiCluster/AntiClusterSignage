package jp.iflink.anticluster_signage.util;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ReflectionUtil {

    public static <T> T getFieldValue(String tag, Class clazz, String name, Object target){
        T value = null;
        try {
            value = getFieldValue(clazz, name, target);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(tag, e.getMessage(), e);
        }
        return value;
    }

    public static <T> T getFieldValue(Class clazz, String name, Object target) throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    public static int getFieldIntValue(Class clazz, String name, Object target) throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    public static void setFieldIntValue(Class clazz, String name, Object target, int value) throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    public static void invokeMethod(Class clazz, String name, Object target, Object... args) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = clazz.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target, args);
    }
}
