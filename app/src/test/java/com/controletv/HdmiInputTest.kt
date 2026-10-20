package com.controletv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdmiInputTest {
    @Test
    fun androidNinePlayerFlag() {
        assertEquals(true, HdmiInput.parse("HdmiCecLocalDevicePlayback:\n  mIsActiveSource: true\n  mAutoTvOff:false"))
        assertEquals(false, HdmiInput.parse("HdmiCecLocalDevicePlayback:\n  mIsActiveSource: false\n"))
    }

    @Test
    fun newerLayoutComparesAddresses() {
        val dump = "mPhysicalAddress: 0x1000\nHdmiCecLocalDevicePlayback:\n  mActiveSource: (0x8, 0x2000)\n"
        assertEquals(false, HdmiInput.parse(dump))
        assertEquals(true, HdmiInput.parse(dump.replace("0x2000", "0x1000")))
    }

    @Test
    fun unknownWhenNothingToGoBy() {
        assertNull(HdmiInput.parse("Can't find service: hdmi_control"))
        assertNull(HdmiInput.parse("mPhysicalAddress: 0x1000\nmActiveSource: (0xf, 0xffff)"))
    }
}
