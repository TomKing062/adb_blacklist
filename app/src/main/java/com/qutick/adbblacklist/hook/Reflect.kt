package com.qutick.adbblacklist.hook

import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun ClassLoader.findClassOrNull(name: String): Class<*>? =
    runCatching { Class.forName(name, false, this) }.getOrNull()

internal fun Class<*>.allFields(): Sequence<Field> = sequence {
    val seen = mutableSetOf<String>()
    var current: Class<*>? = this@allFields
    while (current != null) {
        current.declaredFields.forEach { field ->
            field.isAccessible = true
            val key = field.name + ":" + field.type.name
            if (seen.add(key)) yield(field)
        }
        current = current.superclass
    }
}

internal fun Class<*>.allMethods(): Sequence<Method> = sequence {
    val seen = mutableSetOf<String>()
    var current: Class<*>? = this@allMethods
    while (current != null) {
        current.declaredMethods.forEach { method ->
            method.isAccessible = true
            val signature = method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
            if (seen.add(signature)) yield(method)
        }
        current = current.superclass
    }
}

internal fun Class<*>.fieldOrNull(name: String): Field? =
    allFields().firstOrNull { it.name == name }

internal fun Class<*>.methodOrNull(name: String, vararg parameterTypes: Class<*>): Method? =
    runCatching { getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true } }.getOrNull()
        ?: allMethods().firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(parameterTypes)
        }

internal fun Class<*>.staticIntFieldOrNull(name: String): Int? =
    fieldOrNull(name)?.let { field ->
        runCatching { (field.get(null) as? Number)?.toInt() }.getOrNull()
    }
