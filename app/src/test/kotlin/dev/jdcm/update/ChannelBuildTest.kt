package dev.jdcm.update

import dev.jdcm.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The build being committed names exactly one channel. Caught before the tag is pushed, instead of by every phone on
 * the channel downloading and discarding the build forever (rwilco ChannelBuildTest). Unit tests see the debug
 * BuildConfig: strip a debug versionNameSuffix if the project adds one.
 */
class ChannelBuildTest {
    @Test
    fun `this build belongs to exactly one channel`() {
        val name = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        val channels = UpdateChannel.entries.filter { belongsToChannel(name, it) }
        assertEquals(1, channels.size, "versionName \"$name\" must end in -alpha or -beta")
    }
}
