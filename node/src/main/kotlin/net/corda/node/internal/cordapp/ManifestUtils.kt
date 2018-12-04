package net.corda.node.internal.cordapp

import net.corda.core.internal.cordapp.CordappImpl
import net.corda.core.internal.cordapp.CordappImpl.Cordapp.Companion.UNKNOWN_VALUE
import java.util.jar.Attributes
import java.util.jar.Manifest

fun createTestManifest(name: String, version: String, vendor: String, licence: String, targetVersion: Int): Manifest {
    val manifest = Manifest()

    // Mandatory manifest attribute. If not present, all other entries are silently skipped.
    manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"

    manifest["Cordapp-Contract-Name"] = name
    manifest["Cordapp-Contract-Version"] = version
    manifest["Cordapp-Contract-Vendor"] = vendor
    manifest["Cordapp-Contract-Licence"] = licence

    manifest["Cordapp-Workflow-Name"] = name
    manifest["Cordapp-Workflow-Version"] = version
    manifest["Cordapp-Workflow-Vendor"] = vendor
    manifest["Cordapp-Workflow-Licence"] = licence

    manifest["Target-Platform-Version"] = targetVersion.toString()

    return manifest
}

operator fun Manifest.set(key: String, value: String): String? {
    return mainAttributes.putValue(key, value)
}

operator fun Manifest.get(key: String): String? = mainAttributes.getValue(key)

fun Manifest.toCordappInfo(defaultName: String): CordappImpl.Cordapp {

    val minPlatformVersion = this["Min-Platform-Version"]?.toIntOrNull() ?: 1
    val targetPlatformVersion = this["Target-Platform-Version"]?.toIntOrNull() ?: minPlatformVersion

    /** need to maintain backwards compatibility so use old identifiers if existent */
    if (this["Name"] != null) {
        val shortName = this["Name"] ?: defaultName
        val vendor = this["Implementation-Vendor"] ?: UNKNOWN_VALUE
        val version = this["Implementation-Version"] ?: UNKNOWN_VALUE
        return CordappImpl.Cordapp.Info(
                shortName = shortName,
                vendor = vendor,
                version = version,
                licence = UNKNOWN_VALUE,
                minimumPlatformVersion = minPlatformVersion,
                targetPlatformVersion = targetPlatformVersion
        )
    }

    /** new identifiers (Corda 4) */
    // is it a Contract Jar?
    if (this["Cordapp-Contract-Name"] != null) {
        val name = this["Cordapp-Contract-Name"] ?: defaultName
        val version = Integer.valueOf(this["Cordapp-Contract-Version"]) ?: 1
        val vendor = this["Cordapp-Contract-Vendor"] ?: UNKNOWN_VALUE
        val licence = this["Cordapp-Contract-Licence"] ?: UNKNOWN_VALUE
        return CordappImpl.Cordapp.Contract(
                name = name,
                vendor = vendor,
                version = version,
                licence = licence,
                minimumPlatformVersion = minPlatformVersion,
                targetPlatformVersion = targetPlatformVersion
        )
    }
    // is it a Contract Jar?
    if (this["Cordapp-Worflow-Name"] != null) {
        val name = this["Cordapp-Worflow-Name"] ?: defaultName
        val version = Integer.valueOf(this["Cordapp-Worflow-Version"]) ?: 1
        val vendor = this["Cordapp-Worflow-Vendor"] ?: UNKNOWN_VALUE
        val licence = this["Cordapp-Worflow-Licence"] ?: UNKNOWN_VALUE
        return CordappImpl.Cordapp.Contract(
                name = name,
                vendor = vendor,
                version = version,
                licence = licence,
                minimumPlatformVersion = minPlatformVersion,
                targetPlatformVersion = targetPlatformVersion
        )
    }
    throw IllegalStateException("Missing Manifest attributes for Cordapp: $defaultName")
}
