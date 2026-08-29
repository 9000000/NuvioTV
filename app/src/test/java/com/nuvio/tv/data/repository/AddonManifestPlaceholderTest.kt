package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An installed addon whose manifest cannot be fetched must still be emitted, so it stays visible
 * in the addon manager and can be removed. Before the placeholder fallback it resolved to null and
 * was dropped by filterNotNull(), leaving the URL installed but unreachable from the UI.
 *
 * Uses runBlocking rather than runTest: AddonRepositoryImpl publishes installedAddonsFlow through
 * stateIn() on a scope hardcoded to Dispatchers.IO, so virtual time would not advance it. The
 * withTimeout() calls are therefore scheduling-dependent rather than deterministic.
 *
 * Each repository built here leaks its stateIn collector: syncScope is a SupervisorJob the class
 * never cancels, and there is no close/dispose. Harmless for a handful of instances in a unit-test
 * JVM, but a reason not to grow this file much further without making the scope injectable.
 */
class AddonManifestPlaceholderTest {

    private val addonUrl = "https://addon.example"

    private companion object {
        const val REAL_NAME = "Test Addon"
        const val REAL_VERSION = "1.0.0"
        /** A placeholder carries no version; a resolved manifest does. */
        const val PLACEHOLDER_VERSION = ""
    }

    @Test
    fun `unreachable addon is emitted as a placeholder`() = runBlocking {
        val harness = newRepository()

        val addons = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }

        assertEquals(1, addons.size)
        val addon = addons.single()
        assertEquals(addonUrl, addon.baseUrl)
        assertTrue(addon.enabled)
        // placeholderAddon derives a name from the URL's last path segment, which is what the
        // addon manager renders until a real manifest arrives.
        assertEquals("addon.example", addon.displayName)
        assertEquals(null, addon.logo)
    }

    @Test
    fun `placeholder can be removed`() = runBlocking {
        val harness = newRepository()

        val addon = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }.single()

        // The addon manager drives removal from the emitted row's baseUrl, so this is the
        // property the placeholder exists to preserve: before the fix there was no row to
        // remove from.
        harness.repository.removeAddon(addon.baseUrl)

        coVerify(exactly = 1) { harness.preferences.removeAddon(addonUrl) }
    }

    /**
     * A failed manifest fetch is retried whenever the flow recomputes, because the placeholder is
     * never written to the cache, so the addon stays a cache miss. That is deliberate, and the TTL fix in the sibling commit does not
     * throttle it: an addon added while offline must be able to recover without waiting out a
     * six-hour window that only governs manifests already cached. Pinned so it is not mistaken
     * for a retry storm and optimised away.
     */
    @Test
    fun `failed manifest fetch is retried when the flow recomputes`() = runBlocking {
        val userSetNames = MutableStateFlow<Map<String, String>>(emptyMap())
        val harness = newRepository(userSetNames = userSetNames)

        withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }
        val before = harness.manifestCalls.get()
        assertTrue(before >= 1)

        // Renaming the addon recomputes installedAddonsFlow. Comparing the call count across the
        // mutation ties the extra fetch to the recomputation, rather than merely asserting that
        // two fetches happened at some point.
        userSetNames.value = mapOf(addonUrl to "Renamed")
        withTimeout(5_000) {
            harness.repository.getInstalledAddons()
                .first { list -> list.singleOrNull()?.displayName == "Renamed" }
        }

        assertTrue(harness.manifestCalls.get() > before)
    }

    @Test
    fun `placeholder carries no resources or catalogs`() = runBlocking {
        val harness = newRepository()

        val addon = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }.single()

        // The placeholder has no resources and no catalogs, which supplies the inputs
        // StreamRepositoryImpl uses to exclude it from stream and catalog processing -
        // supportsStreamResource() reads resources, and catalog rows are built from catalogs.
        // That predicate is private to StreamRepositoryImpl, so this asserts its inputs rather
        // than the exclusion itself.
        assertTrue(addon.resources.isEmpty())
        assertTrue(addon.catalogs.isEmpty())
    }

    /**
     * The placeholder must be a temporary stand-in, not a sticky one: once the manifest becomes
     * reachable, the real addon has to replace it. The placeholder is never written to the cache,
     * so the next recomputation refetches and the resolved manifest wins.
     */
    @Test
    fun `placeholder is replaced once the manifest becomes available`() = runBlocking {
        val userSetNames = MutableStateFlow<Map<String, String>>(emptyMap())
        val harness = newRepository(userSetNames = userSetNames)

        val placeholder = withTimeout(5_000) {
            harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        }.single()
        assertEquals(PLACEHOLDER_VERSION, placeholder.version)

        harness.reachable.set(true)
        // Names a different key, so the recomputation is triggered without renaming this addon.
        userSetNames.value = mapOf("https://other.example" to "Other")

        val resolved = withTimeout(5_000) {
            harness.repository.getInstalledAddons()
                .first { list -> list.singleOrNull()?.version == REAL_VERSION }
        }.single()
        assertEquals(REAL_NAME, resolved.name)
        assertEquals(addonUrl, resolved.baseUrl)
    }

    private data class Harness(
        val repository: AddonRepositoryImpl,
        val preferences: AddonPreferences,
        val manifestCalls: AtomicInteger,
        val reachable: AtomicBoolean
    )

    private fun newRepository(
        userSetNames: kotlinx.coroutines.flow.Flow<Map<String, String>> = flowOf(emptyMap()),
        reachable: Boolean = false
    ): Harness {
        val manifestCalls = AtomicInteger()
        val isReachable = AtomicBoolean(reachable)
        val api = mockk<AddonApi>()
        coEvery { api.getManifest(any()) } coAnswers {
            manifestCalls.incrementAndGet()
            if (!isReachable.get()) throw IOException("offline")
            Response.success(
                AddonManifestDto(id = "test-addon", name = REAL_NAME, version = REAL_VERSION)
            )
        }

        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns flowOf(listOf(addonUrl))
        every { preferences.userSetNames } returns userSetNames
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())
        coEvery { preferences.removeAddon(any()) } returns true

        return Harness(
            repository = AddonRepositoryImpl(
                api = api,
                preferences = preferences,
                addonSyncService = mockk<AddonSyncService>(relaxed = true),
                authManager = mockk<AuthManager>(relaxed = true),
                context = mockk<Context>(relaxed = true)
            ),
            preferences = preferences,
            manifestCalls = manifestCalls,
            reachable = isReachable
        )
    }
}
