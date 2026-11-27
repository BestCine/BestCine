package com.nuvio.app.bestcine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BestCineAnimeIdResolverTest {
    @Test
    fun `detects anime prefixes and rejects non-anime ids`() {
        assertTrue(BestCineAnimeIdResolver.isAnimeMetaId("kitsu:41370"))
        assertTrue(BestCineAnimeIdResolver.isAnimeMetaId("mal:38000:5"))
        assertTrue(BestCineAnimeIdResolver.isAnimeMetaId("anilist:101922"))
        assertTrue(BestCineAnimeIdResolver.isAnimeMetaId("anidb:14107"))
        assertFalse(BestCineAnimeIdResolver.isAnimeMetaId("tt9335498"))
        assertFalse(BestCineAnimeIdResolver.isAnimeMetaId("tmdb:85937"))
        assertFalse(BestCineAnimeIdResolver.isAnimeMetaId("bestcine_tv_1"))
    }

    @Test
    fun `parses source and numeric base id ignoring episode suffix`() {
        assertEquals("kitsu" to "41370", BestCineAnimeIdResolver.parseSourceAndId("kitsu:41370:5"))
        assertEquals("myanimelist" to "38000", BestCineAnimeIdResolver.parseSourceAndId("mal:38000"))
        assertEquals("anilist" to "101922", BestCineAnimeIdResolver.parseSourceAndId("anilist:101922:1:3"))
        assertNull(BestCineAnimeIdResolver.parseSourceAndId("tt9335498"))
        assertNull(BestCineAnimeIdResolver.parseSourceAndId("kitsu:invalid"))
    }

    @Test
    fun `parses ARM json object and array payloads`() {
        val objectPayload = """{"imdb":"tt9335498","kitsu":41370,"myanimelist":38000,"thetvdb-season":1,"themoviedb-season":1}"""
        val parsedObj = BestCineAnimeIdResolver.parseArmPayload(objectPayload)
        assertEquals(ArmResolvedEntry(imdbId = "tt9335498", season = 1), parsedObj)

        val mismatchSeasonPayload = """{"imdb":"tt2560140","thetvdb-season":3,"themoviedb-season":4}"""
        val parsedMismatch = BestCineAnimeIdResolver.parseArmPayload(mismatchSeasonPayload)
        assertEquals(ArmResolvedEntry(imdbId = "tt2560140", season = null), parsedMismatch)

        val arrayPayload = """[{"imdb":"tt0388629","kitsu":12,"themoviedb-season":1}]"""
        val parsedArr = BestCineAnimeIdResolver.parseArmPayload(arrayPayload)
        assertEquals(ArmResolvedEntry(imdbId = "tt0388629", season = 1), parsedArr)

        assertNull(BestCineAnimeIdResolver.parseArmPayload("""{"kitsu":12}"""))
    }
}
