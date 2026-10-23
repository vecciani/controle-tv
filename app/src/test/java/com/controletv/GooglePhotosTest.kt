package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Test

class GooglePhotosTest {
    @Test
    fun findsTheAlbumPhotosOnly() {
        val page = """
            AF_initDataCallback({key: 'ds:1', data:[null,[
            ["AF1QipOne_1",["https://lh3.googleusercontent.com/pw/AP1GczAAA",4032,3024,null,null,null,null,null,[1]],1690000000000],
            ["AF1QipTwo-2",["https://lh3.googleusercontent.com/pw/AP1GczBBB",1080,1920],1690000000001],
            ["AF1QipOne_1",["https://lh3.googleusercontent.com/pw/AP1GczAAA",4032,3024],1690000000000],
            ["AF1QipTiny",["https://lh3.googleusercontent.com/pw/AP1GczCCC",96,96],1],
            ["https://lh3.googleusercontent.com/a/ACg8ocOwner",200,200]
            ]]});
        """
        val photos = GooglePhotos.parseAlbum(page)
        assertEquals(listOf("AF1QipOne_1", "AF1QipTwo-2"), photos.map { it.id })
        assertEquals("https://lh3.googleusercontent.com/pw/AP1GczBBB", photos[1].url)
        assertEquals(1920, photos[1].height)
    }
}
