package com.fusion.melodyLinkNeo.definition.packages

/**
 * Structured failure model for `.dcpkg` loading, validation and registration.
 *
 * The UI is expected to render [code], [path] and [detail] instead of a raw exception message.
 */
sealed interface PackageError {
    val code: Code
    val path: String?
    val detail: String

    enum class Code {
        INVALID_ZIP,
        MISSING_ENTRY,
        UNSUPPORTED_FORMAT,
        UNSAFE_PATH,
        METADATA_MISMATCH,
        DEFINITION_INVALID,
        PACKAGE_CONFLICT,
        SIZE_LIMIT_EXCEEDED,
        STORAGE_FAILURE,
    }

    data class InvalidZip(override val detail: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.INVALID_ZIP
    }

    data class MissingEntry(val entryName: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.MISSING_ENTRY
        override val detail: String get() = "missing required entry '$entryName'"
    }

    data class UnsupportedFormat(override val detail: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.UNSUPPORTED_FORMAT
    }

    data class UnsafePath(val entryName: String, override val detail: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.UNSAFE_PATH
    }

    data class MetadataMismatch(override val detail: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.METADATA_MISMATCH
    }

    data class DefinitionInvalid(override val path: String, override val detail: String) : PackageError {
        override val code: Code get() = Code.DEFINITION_INVALID
    }

    data class PackageConflict(
        val packageId: String,
        override val detail: String,
        override val path: String? = null,
    ) : PackageError {
        override val code: Code get() = Code.PACKAGE_CONFLICT
    }

    data class SizeLimitExceeded(override val detail: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.SIZE_LIMIT_EXCEEDED
    }

    data class StorageFailure(override val detail: String, override val path: String? = null) : PackageError {
        override val code: Code get() = Code.STORAGE_FAILURE
    }
}

/** Thrown by package loading and registration APIs. Carries a structured [PackageError]. */
class PackageException(val error: PackageError) :
    IllegalStateException("${error.code.name}: ${error.detail}")

internal fun Throwable.asPackageError(sourceName: String? = null): PackageError = when (this) {
    is PackageException -> error
    else -> PackageError.InvalidZip(
        detail = message ?: (this::class.simpleName ?: "unknown error"),
        path = sourceName,
    )
}
