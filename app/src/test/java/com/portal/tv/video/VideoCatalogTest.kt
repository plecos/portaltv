package com.portal.tv.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCatalogTest {

    private val pexels = """{"page":1,"per_page":2,"videos":[
      {"id":3045163,"width":4096,"height":2160,"duration":14,"url":"https://www.pexels.com/video/aurora-over-a-lake-3045163/",
       "image":"https://images.pexels.com/videos/3045163/pic.jpg",
       "user":{"id":1,"name":"Jane Doe","url":"https://www.pexels.com/@jane"},
       "video_files":[
         {"id":1,"quality":"uhd","file_type":"video/mp4","width":4096,"height":2160,"link":"https://v.pexels.com/uhd.mp4"},
         {"id":2,"quality":"hd","file_type":"video/mp4","width":1920,"height":1080,"link":"https://v.pexels.com/hd.mp4"},
         {"id":3,"quality":"sd","file_type":"video/mp4","width":960,"height":540,"link":"https://v.pexels.com/sd.mp4"},
         {"id":4,"quality":"hd","file_type":"video/webm","width":1920,"height":1080,"link":"https://v.pexels.com/hd.webm"}]},
      {"id":2,"duration":5,"url":"https://www.pexels.com/video/x-2/","image":"","user":{"name":"Bo","url":"https://www.pexels.com/@bo"},
       "video_files":[{"id":9,"quality":null,"file_type":"video/mp4","width":1080,"height":1920,"link":"https://v.pexels.com/tall.mp4"}]}
    ]}"""

    @Test fun `a Pexels result picks 1080p mp4 over 4K, smaller and other types`() {
        val hit = VideoCatalog.parsePexels(pexels).first()
        assertEquals("https://v.pexels.com/hd.mp4", hit.fileUrl)
        assertEquals(1080, hit.height)
        assertEquals("3045163", hit.id)
        assertEquals("Aurora over a lake", hit.title)
        assertEquals("Jane Doe", hit.creator)
        assertEquals("https://www.pexels.com/@jane", hit.creatorUrl)
        assertEquals("dl_pexels_3045163.mp4", hit.fileName)
        assertEquals(14, hit.seconds)
    }

    @Test fun `a portrait-only video is left out`() {
        assertEquals(1, VideoCatalog.parsePexels(pexels).size)
    }

    private val pixabay = """{"total":1,"hits":[
      {"id":5555,"pageURL":"https://pixabay.com/videos/stars-sky-5555/","tags":"stars, sky, night, space","duration":22,
       "user":"someone","user_id":123,
       "videos":{
         "large":{"url":"https://cdn.pixabay.com/large.mp4","width":3840,"height":2160,"size":90000000,"thumbnail":"https://cdn.pixabay.com/large.jpg"},
         "medium":{"url":"https://cdn.pixabay.com/medium.mp4","width":1920,"height":1080,"size":42000000,"thumbnail":"https://cdn.pixabay.com/medium.jpg"},
         "small":{"url":"https://cdn.pixabay.com/small.mp4","width":1280,"height":720,"size":20000000,"thumbnail":""},
         "tiny":{"url":"","width":640,"height":360,"size":0,"thumbnail":""}}}]}"""

    @Test fun `a Pixabay result picks the 1080p file and knows its size`() {
        val hit = VideoCatalog.parsePixabay(pixabay).single()
        assertEquals("https://cdn.pixabay.com/medium.mp4", hit.fileUrl)
        assertEquals(42_000_000L, hit.bytes)
        assertEquals("Stars, sky, night", hit.title)
        assertEquals("https://pixabay.com/users/someone-123/", hit.creatorUrl)
        assertEquals("https://cdn.pixabay.com/medium.jpg", hit.thumbUrl)
        assertEquals("dl_pixabay_5555.mp4", hit.fileName)
    }

    @Test fun `an unexpected answer gives no results`() {
        assertTrue(VideoCatalog.parsePexels("not json").isEmpty())
        assertTrue(VideoCatalog.parsePixabay("""{"error":"bad key"}""").isEmpty())
        assertTrue(VideoCatalog.parsePexels("""{"videos":[{"id":1}]}""").isEmpty())
    }

    @Test fun `search addresses`() {
        assertEquals(
            "https://api.pexels.com/v1/videos/search?query=night+sky&orientation=landscape&per_page=24&page=2",
            VideoCatalog.pexelsUrl("https://api.pexels.com/v1", " night sky ", 2, 24)
        )
        assertEquals(
            "https://api.pexels.com/v1/videos/popular?per_page=24&page=1",
            VideoCatalog.pexelsUrl("https://api.pexels.com/v1", "", 1, 24)
        )
        assertEquals(
            "https://pixabay.com/api/videos/?key=K&q=aurora&safesearch=true&per_page=24&page=1",
            VideoCatalog.pixabayUrl("https://pixabay.com", "K", "aurora", 1, 24)
        )
    }

    @Test fun `durations and sizes read well`() {
        assertEquals("1:05", VideoCatalog.duration(65))
        assertEquals("0:09", VideoCatalog.duration(9))
        assertEquals("42 MB", VideoCatalog.size(42_000_000))
        assertEquals("1.2 GB", VideoCatalog.size(1_200_000_000))
        assertEquals("unknown size", VideoCatalog.size(0))
    }

    private val nasa = """{"collection":{"version":"1.0","items":[
      {"href":"https://images-assets.nasa.gov/video/KSC-1/collection.json",
       "data":[{"center":"KSC","title":"  Aurora seen from the station ","nasa_id":"KSC-1","media_type":"video","photographer":"Jane Q. Astronaut"}],
       "links":[{"href":"https://images-assets.nasa.gov/video/KSC-1/KSC-1~thumb.jpg","rel":"preview","render":"image"}]},
      {"href":"https://images-assets.nasa.gov/image/IMG-2/collection.json",
       "data":[{"title":"A photo","nasa_id":"IMG-2","media_type":"image"}]},
      {"href":"http://images-assets.nasa.gov/video/X 3/collection.json",
       "data":[{"title":"Insecure","nasa_id":"X 3","media_type":"video"}]},
      {"href":"https://images-assets.nasa.gov/video/GSFC_3/collection.json",
       "data":[{"center":"GSFC","title":"Earth rotating","nasa_id":"GSFC/3?x","media_type":"video"}]}
    ]}}"""

    @Test fun `NASA results keep videos and fill in the creator and page`() {
        val hits = VideoCatalog.parseNasa(nasa)
        assertEquals(listOf("KSC-1", "GSFC_3_x"), hits.map { it.id })
        val first = hits.first()
        assertEquals("Aurora seen from the station", first.title)
        assertEquals("NASA / Jane Q. Astronaut", first.creator)
        assertEquals("https://images.nasa.gov/details/KSC-1", first.pageUrl)
        assertEquals("https://images-assets.nasa.gov/video/KSC-1/KSC-1~thumb.jpg", first.thumbUrl)
        assertEquals("https://images-assets.nasa.gov/video/KSC-1/collection.json", first.manifestUrl)
        assertEquals("", first.fileUrl)
        assertEquals("NASA / GSFC", hits.last().creator)
        assertEquals("dl_nasa_KSC-1.mp4", first.fileName)
    }

    @Test fun `NASA files are chosen largest first, as https mp4 and never the original`() {
        val manifest = """["http://x.nasa.gov/v/KSC-1~orig.mp4","http://x.nasa.gov/v/KSC-1~small.mp4",
            "http://x.nasa.gov/v/KSC-1~large.mp4","http://x.nasa.gov/v/KSC-1~medium.mp4",
            "http://x.nasa.gov/v/KSC-1~thumb.jpg","http://x.nasa.gov/v/KSC-1.srt","http://x.nasa.gov/v/KSC-1~mobile.mp4"]"""
        assertEquals(
            listOf("https://x.nasa.gov/v/KSC-1~large.mp4", "https://x.nasa.gov/v/KSC-1~medium.mp4",
                "https://x.nasa.gov/v/KSC-1~small.mp4", "https://x.nasa.gov/v/KSC-1~mobile.mp4"),
            VideoCatalog.nasaFiles(manifest)
        )
        assertTrue(VideoCatalog.nasaFiles("not json").isEmpty())
    }

    @Test fun `NASA search address falls back to a default search`() {
        assertEquals(
            "https://images-api.nasa.gov/search?q=earth+from+space&media_type=video&page_size=24&page=1",
            VideoCatalog.nasaUrl("https://images-api.nasa.gov", "  ", 1, 24)
        )
        assertEquals(
            "https://images-api.nasa.gov/search?q=aurora&media_type=video&page_size=24&page=3",
            VideoCatalog.nasaUrl("https://images-api.nasa.gov", "aurora", 3, 24)
        )
    }

    @Test fun `a video of unknown length shows no time`() {
        assertEquals("", VideoCatalog.duration(0))
        val hit = VideoCatalog.parseNasa(nasa).first()
        assertEquals("", hit.details)
    }
}
