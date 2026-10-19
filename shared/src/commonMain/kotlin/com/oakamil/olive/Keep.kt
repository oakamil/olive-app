package com.oakamil.olive

@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(
    AnnotationTarget.FILE,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FIELD
)
@Retention(AnnotationRetention.BINARY)
expect annotation class Keep()
