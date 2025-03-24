package com.toycontainer.image

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * Type adapter for Instant to enable JSON serialization.
 */
class InstantAdapter : TypeAdapter<Instant>() {
    override fun write(out: JsonWriter, value: Instant?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toString())
        }
    }

    override fun read(input: JsonReader): Instant? {
        return if (input.peek() == com.google.gson.stream.JsonToken.NULL) {
            input.nextNull()
            null
        } else {
            Instant.parse(input.nextString())
        }
    }
}

/**
 * Manages storage operations for container images.
 */
class ImageStorage {
    private val baseDir = "/var/lib/toy-container/images"
    private val layersDir = "$baseDir/layers"
    private val metadataDir = "$baseDir/metadata"
    private val tempDir = "$baseDir/tmp"
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(Instant::class.java, InstantAdapter())
        .create()

    init {
        // Create necessary directories
        runCommand("sudo", "mkdir", "-p", baseDir)
        runCommand("sudo", "mkdir", "-p", layersDir)
        runCommand("sudo", "mkdir", "-p", metadataDir)
        runCommand("sudo", "mkdir", "-p", tempDir)

        // Set permissions to allow write access
        runCommand("sudo", "chmod", "-R", "777", baseDir)
    }

    /**
     * Run a command with the given arguments.
     * @param command The command to run
     * @param args The arguments for the command
     * @return True if the command succeeded, false otherwise
     */
    private fun runCommand(vararg command: String): Boolean {
        try {
            val process = ProcessBuilder(*command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            return process.waitFor() == 0
        } catch (e: Exception) {
            println("Warning: Failed to run command: ${command.joinToString(" ")}")
            return false
        }
    }

    /**
     * Build an image from a directory.
     * @param sourceDir Directory containing the files for the image
     * @param name Name for the image
     * @param tag Tag for the image
     * @param metadata Additional metadata for the image
     * @return Result of the image build operation
     */
    fun buildImage(
        sourceDir: String,
        name: String,
        tag: String = "latest",
        metadata: Map<String, String> = mapOf()
    ): ImageOperationResult {
        try {
            val imageId = UUID.randomUUID().toString()
            val layerId = UUID.randomUUID().toString()
            val now = Instant.now()

            // Create layer
            val layerPath = "$layersDir/$layerId.tar.gz"
            createTarGz(sourceDir, layerPath)

            // Calculate size
            val size = File(layerPath).length()

            // Create image metadata
            val image = Image(
                id = imageId,
                name = name,
                tag = tag,
                size = size,
                createdAt = now,
                layerIds = listOf(layerId),
                metadata = metadata
            )

            // Save image metadata
            saveImageMetadata(image)

            return ImageOperationResult(
                status = ImageOperationStatus.SUCCESS,
                message = "Image ${image.getFullName()} built successfully",
                image = image
            )
        } catch (e: Exception) {
            return ImageOperationResult(
                status = ImageOperationStatus.FAILURE,
                message = "Failed to build image: ${e.message}",
                image = null
            )
        }
    }

    /**
     * List all available images.
     * @return List of images
     */
    fun listImages(): List<Image> {
        val result = mutableListOf<Image>()
        val metadataDirectory = File(metadataDir)

        if (!metadataDirectory.exists() || !metadataDirectory.isDirectory) {
            return result
        }

        metadataDirectory.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val json = file.readText()
                val image = gson.fromJson(json, Image::class.java)
                result.add(image)
            } catch (e: Exception) {
                println("Warning: Failed to parse image metadata file ${file.name}: ${e.message}")
            }
        }

        return result
    }

    /**
     * Get an image by name and tag.
     * @param name Name of the image
     * @param tag Tag of the image
     * @return The image if found, null otherwise
     */
    fun getImage(name: String, tag: String = "latest"): Image? {
        return listImages().find { it.name == name && it.tag == tag }
    }

    /**
     * Delete an image.
     * @param name Name of the image
     * @param tag Tag of the image
     * @return Result of the delete operation
     */
    fun deleteImage(name: String, tag: String = "latest"): ImageOperationResult {
        val image = getImage(name, tag) ?: return ImageOperationResult(
            status = ImageOperationStatus.NOT_FOUND,
            message = "Image $name:$tag not found",
            image = null
        )

        try {
            // Delete layer files
            image.layerIds.forEach { layerId ->
                val layerFile = File("$layersDir/$layerId.tar.gz")
                if (layerFile.exists()) {
                    layerFile.delete()
                }
            }

            // Delete metadata file
            val metadataFile = File("$metadataDir/${image.id}.json")
            if (metadataFile.exists()) {
                metadataFile.delete()
            }

            return ImageOperationResult(
                status = ImageOperationStatus.SUCCESS,
                message = "Image ${image.getFullName()} deleted successfully",
                image = null
            )
        } catch (e: Exception) {
            return ImageOperationResult(
                status = ImageOperationStatus.FAILURE,
                message = "Failed to delete image: ${e.message}",
                image = null
            )
        }
    }

    /**
     * Extract an image to a target directory.
     * @param name Name of the image
     * @param tag Tag of the image
     * @param targetDir Directory to extract the image to
     * @return Result of the extract operation
     */
    fun extractImage(name: String, tag: String = "latest", targetDir: String): ImageOperationResult {
        val image = getImage(name, tag) ?: return ImageOperationResult(
            status = ImageOperationStatus.NOT_FOUND,
            message = "Image $name:$tag not found",
            image = null
        )

        try {
            // Create target directory
            File(targetDir).mkdirs()

            // Extract each layer
            image.layerIds.forEach { layerId ->
                val layerFile = File("$layersDir/$layerId.tar.gz")
                if (layerFile.exists()) {
                    extractTarGz(layerFile.absolutePath, targetDir)
                }
            }

            return ImageOperationResult(
                status = ImageOperationStatus.SUCCESS,
                message = "Image ${image.getFullName()} extracted successfully",
                image = image
            )
        } catch (e: Exception) {
            return ImageOperationResult(
                status = ImageOperationStatus.FAILURE,
                message = "Failed to extract image: ${e.message}",
                image = null
            )
        }
    }

    /**
     * Save image metadata to disk.
     * @param image Image to save metadata for
     */
    private fun saveImageMetadata(image: Image) {
        val json = gson.toJson(image)
        File("$metadataDir/${image.id}.json").writeText(json)
    }

    /**
     * Create a tar.gz archive from a directory.
     * @param sourceDir Directory to archive
     * @param outputFile Output file path
     */
    private fun createTarGz(sourceDir: String, outputFile: String) {
        FileOutputStream(outputFile).use { fileOut ->
            GZIPOutputStream(fileOut).use { gzipOut ->
                TarArchiveOutputStream(gzipOut).use { tarOut ->
                    val sourcePath = Paths.get(sourceDir)

                    Files.walk(sourcePath).filter { !Files.isDirectory(it) }.forEach { path ->
                        val entryName = sourcePath.relativize(path).toString().replace('\\', '/')
                        val entry = TarArchiveEntry(path.toFile(), entryName)
                        tarOut.putArchiveEntry(entry)
                        Files.copy(path, tarOut)
                        tarOut.closeArchiveEntry()
                    }

                    tarOut.finish()
                }
            }
        }
    }

    /**
     * Extract a tar.gz archive to a directory.
     * @param archiveFile Archive file to extract
     * @param outputDir Directory to extract to
     */
    private fun extractTarGz(archiveFile: String, outputDir: String) {
        FileInputStream(archiveFile).use { fileIn ->
            GZIPInputStream(fileIn).use { gzipIn ->
                TarArchiveInputStream(gzipIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry

                    while (entry != null) {
                        val outputFile = File(outputDir, entry.name)

                        if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile.mkdirs()
                            FileOutputStream(outputFile).use { output ->
                                tarIn.copyTo(output)
                            }
                        }

                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }
}