package com.nuvio.app.features.addons

import com.nuvio.app.features.streams.isAllowedStreamProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogOnlyAddonPolicyTest {

    private fun manifestWith(
        id: String,
        name: String,
        resources: List<String>,
        catalogCount: Int = 1,
        url: String = "https://example.com/$id/manifest.json",
    ): AddonManifest = AddonManifest(
        id = id,
        name = name,
        description = "",
        version = "1.0.0",
        resources = resources.map { AddonResource(name = it, types = listOf("movie", "series")) },
        types = listOf("movie", "series"),
        catalogs = (1..catalogCount).map { idx ->
            AddonCatalog(type = "movie", id = "cat_$idx", name = "Catálogo $idx")
        },
        transportUrl = url,
    )

    @Test
    fun `manifest policy accepts catalog-only addons and blocks stream addons`() {
        val tmdbCatalog = manifestWith(
            id = "tmdb.catalog",
            name = "TMDB Catalog",
            resources = listOf("catalog", "meta"),
            catalogCount = 2,
        )
        assertFalse(tmdbCatalog.declaresStreamResource)
        assertTrue(tmdbCatalog.isCatalogOnlyAllowed)

        val streamOnly = manifestWith(
            id = "torrentio",
            name = "Torrentio",
            resources = listOf("stream"),
            catalogCount = 0,
        )
        assertTrue(streamOnly.declaresStreamResource)
        assertFalse(streamOnly.isCatalogOnlyAllowed)

        val hybridCatalogAndStream = manifestWith(
            id = "hybrid.addon",
            name = "Hybrid",
            resources = listOf("catalog", "stream"),
            catalogCount = 2,
        )
        assertTrue(hybridCatalogAndStream.declaresStreamResource)
        assertFalse(hybridCatalogAndStream.isCatalogOnlyAllowed)

        val subtitlesOnlyNoCatalog = manifestWith(
            id = "subs.only",
            name = "Subtitles",
            resources = listOf("subtitles"),
            catalogCount = 0,
        )
        assertFalse(subtitlesOnlyNoCatalog.declaresStreamResource)
        assertTrue(subtitlesOnlyNoCatalog.isCatalogOnlyAllowed)

        val metaOnlyNoCatalog = manifestWith(
            id = "meta.only",
            name = "Meta Provider",
            resources = listOf("meta"),
            catalogCount = 0,
        )
        assertFalse(metaOnlyNoCatalog.declaresStreamResource)
        assertTrue(metaOnlyNoCatalog.isCatalogOnlyAllowed)
    }

    @Test
    fun `safe push preserves remote stream addons, excludes BestCine url, and syncs catalog changes`() {
        val isBestCine: (String) -> Boolean = { url ->
            url.contains("server.bestcine.test", ignoreCase = true) || url.contains("/bc_")
        }

        val remoteRows = listOf(
            AddonRow(url = "https://torrentio.strem.fun/manifest.json", name = "Torrentio", enabled = true, sortOrder = 0),
            AddonRow(url = "https://catalog-old.example.com/manifest.json", name = "Old Catalog", enabled = true, sortOrder = 1),
            AddonRow(url = "https://catalog-keep.example.com/manifest.json", name = "Keep Catalog", enabled = true, sortOrder = 2),
        )

        val localAddons = listOf(
            ManagedAddon(
                manifestUrl = "https://server.bestcine.test/bc_secret123/manifest.json",
                manifest = manifestWith("bestcine", "BestCine", listOf("catalog", "stream"), 3, "https://server.bestcine.test/bc_secret123/manifest.json"),
                enabled = true,
            ),
            ManagedAddon(
                manifestUrl = "https://catalog-keep.example.com/manifest.json",
                manifest = manifestWith("keep", "Keep Catalog", listOf("catalog"), 1, "https://catalog-keep.example.com/manifest.json"),
                enabled = false,
            ),
            ManagedAddon(
                manifestUrl = "https://catalog-new.example.com/manifest.json",
                manifest = manifestWith("new", "New Catalog", listOf("catalog", "meta"), 2, "https://catalog-new.example.com/manifest.json"),
                enabled = true,
            ),
        )

        val merged = mergeRemoteAndLocalForSafePush(
            remoteRows = remoteRows,
            localExternalCatalogAddons = localAddons,
            removedCanonicalKeys = setOf(canonicalAddonKey("https://catalog-old.example.com/manifest.json")),
            isBestCineUrl = isBestCine,
        )

        assertEquals(3, merged.size)
        // Remote stream addon preserved untouched!
        assertEquals("https://torrentio.strem.fun/manifest.json", merged[0].url)
        assertEquals(true, merged[0].enabled)
        assertEquals(0, merged[0].sortOrder)
        // Existing catalog addon updated with local enabled=false!
        assertEquals("https://catalog-keep.example.com/manifest.json", merged[1].url)
        assertEquals(false, merged[1].enabled)
        assertEquals(1, merged[1].sortOrder)
        // Newly added catalog addon appended!
        assertEquals("https://catalog-new.example.com/manifest.json", merged[2].url)
        assertEquals(true, merged[2].enabled)
        assertEquals(2, merged[2].sortOrder)
        // BestCine manifest NEVER uploaded!
        assertTrue(merged.none { it.url.contains("bestcine") || it.url.contains("/bc_") })
        // Removed catalog addon omitted!
        assertTrue(merged.none { it.url.contains("catalog-old") })
    }

    @Test
    fun `layer 2 playback stream lock allows only official BestCine addon`() {
        val official = ManagedAddon(
            manifestUrl = "https://server.bestcine.test/bc_token123/manifest.json",
            manifest = manifestWith("bestcine", "BestCine", listOf("catalog", "stream"), 2, "https://server.bestcine.test/bc_token123/manifest.json"),
            enabled = true,
        )
        val externalSneakyStream = ManagedAddon(
            manifestUrl = "https://sneaky.example.com/manifest.json",
            manifest = manifestWith("sneaky", "Sneaky", listOf("catalog", "stream"), 1, "https://sneaky.example.com/manifest.json"),
            enabled = true,
        )

        assertTrue(official.isAllowedStreamProvider())
        assertFalse(externalSneakyStream.isAllowedStreamProvider())
    }
}
