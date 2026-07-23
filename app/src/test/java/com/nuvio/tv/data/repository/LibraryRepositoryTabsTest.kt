package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRepositoryTabsTest {
    @Test
    fun `screen tabs follow active provider while membership tabs include every connection`() = runTest {
        val sourceMode = MutableStateFlow(LibrarySourceMode.SIMKL)
        val traktTab = tab("trakt:watchlist", TrackingProviderId.TRAKT)
        val simklTab = tab("simkl:plantowatch", TrackingProviderId.SIMKL)
        val repository = repository(
            sourceMode = sourceMode,
            providers = setOf(
                FakeLibraryProvider(TrackingProviderId.TRAKT, traktTab),
                FakeLibraryProvider(TrackingProviderId.SIMKL, simklTab)
            )
        )

        assertEquals(listOf(simklTab), repository.listTabs.first())
        assertEquals(listOf(traktTab, simklTab), repository.membershipListTabs.first())

        sourceMode.value = LibrarySourceMode.TRAKT

        assertEquals(listOf(traktTab), repository.listTabs.first())
        assertEquals(listOf(traktTab, simklTab), repository.membershipListTabs.first())
    }

    private fun repository(
        sourceMode: MutableStateFlow<LibrarySourceMode>,
        providers: Set<TrackingLibraryProvider>
    ): LibraryRepositoryImpl {
        val settings = mockk<TraktSettingsDataStore>(relaxed = true) {
            every { librarySourceMode } returns sourceMode
        }
        return LibraryRepositoryImpl(
            appContext = mockk<Context>(relaxed = true),
            libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
            traktAuthDataStore = mockk<TraktAuthDataStore>(relaxed = true),
            traktSettingsDataStore = settings,
            traktLibraryService = mockk<TraktLibraryService>(relaxed = true),
            librarySyncService = mockk<LibrarySyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            metaRepository = mockk<MetaRepository>(relaxed = true),
            trackingProviders = TrackingLibraryProviderRegistry(providers)
        )
    }

    private fun tab(key: String, providerId: TrackingProviderId) = LibraryListTab(
        key = key,
        title = key,
        type = LibraryListTab.Type.WATCHLIST,
        trackingProviderId = providerId.storageId
    )

    private class FakeLibraryProvider(
        override val providerId: TrackingProviderId,
        tab: LibraryListTab
    ) : TrackingLibraryProvider {
        override val isAuthenticated = flowOf(true)
        override val isRefreshing = flowOf(false)
        override val items = flowOf(emptyList<LibraryEntry>())
        override val tabs = flowOf(listOf(tab))

        override fun recognizesListKey(key: String): Boolean = true

        override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
            flowOf(emptySet())

        override suspend fun toggleDefault(item: LibraryEntryInput) = Unit

        override suspend fun getMembershipSnapshot(item: LibraryEntryInput) = ListMembershipSnapshot()

        override suspend fun applyMembershipChanges(
            item: LibraryEntryInput,
            changes: ListMembershipChanges,
            destructiveRemovalConfirmed: Boolean
        ) = Unit

        override suspend fun refresh(intent: TrackingRefreshIntent) = Unit
    }
}
