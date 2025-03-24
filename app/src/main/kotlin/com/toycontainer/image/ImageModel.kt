package com.toycontainer.image

import java.time.Instant

/**
 * Represents a container image with its metadata.
 */
data class Image(
    val id: String,
    val name: String,
    val tag: String = "latest",
    val size: Long,
    val createdAt: Instant,
    val layerIds: List<String>,
    val metadata: Map<String, String> = mapOf()
) {
    /**
     * Get the full name of the image including tag.
     */
    fun getFullName(): String = "$name:$tag"

    /**
     * Get the path of the image in the local storage.
     */
    fun getLocalPath(baseDir: String): String = "$baseDir/$id"
}

/**
 * Represents a layer in a container image.
 */
data class Layer(
    val id: String,
    val diffId: String,
    val size: Long,
    val createdAt: Instant,
    val parentId: String? = null
)

/**
 * Represents the status of an image operation.
 */
enum class ImageOperationStatus {
    SUCCESS,
    FAILURE,
    NOT_FOUND,
    ALREADY_EXISTS
}

/**
 * Result of an image operation.
 */
data class ImageOperationResult(
    val status: ImageOperationStatus,
    val message: String,
    val image: Image? = null
)