package com.controletv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonPhotosTest {
    @Test
    fun recognizesAmazonPhotosLinks() {
        assertTrue(AmazonPhotos.isLink("https://www.amazon.com/photos/share/AbC123xyz"))
        assertTrue(AmazonPhotos.isLink("https://www.amazon.co.uk/photos/share/AbC123xyz"))
        assertTrue(AmazonPhotos.isLink("https://amzn.eu/d/abc"))
        assertFalse(AmazonPhotos.isLink("https://photos.app.goo.gl/abc"))
        assertFalse(AmazonPhotos.isLink("https://www.amazon.com/dp/B0123"))
    }
}
