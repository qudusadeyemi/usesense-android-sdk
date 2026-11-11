package com.usesense.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BenchLabelTest {
    @Test
    fun `labels from the tagging doc pass`() {
        assertNull(BenchLabel.problem("lsb3_r1_s03_a03_photo-gloss_L2_0412"))
        assertNull(BenchLabel.problem("lsb3_r2_s10_a03_genuine_L1_0001"))
        assertNull(BenchLabel.problem("lsb3_r1_s01_a03_screen-tablet_L3_0099"))
        assertNull(BenchLabel.problem("lsb3_r1_s01_a03_cutout_L1_0100"))
        assertNull(BenchLabel.problem("lsb3_r1_s01_a03_inject-vcam-renamed_L1_0101"))
        assertNull(BenchLabel.problem("lsb3_r1_s01_newphone_mask-resin_L2_0102"))
    }

    @Test
    fun `a plain user id isn't a bench label`() {
        assertNull(BenchLabel.problem("demo_user_001"))
        assertNull(BenchLabel.problem(""))
    }

    @Test
    fun `typos are caught`() {
        assertNotNull(BenchLabel.problem("LSB3_r1_s03_a03_photo-gloss_L2_0412"))
        assertNotNull(BenchLabel.problem("lsb3_r1_s03_a03_photo_gloss_L2_0412")) // underscore inside the class
        assertNotNull(BenchLabel.problem("lsb3_r1_s11_a03_genuine_L2_0412"))
        assertNotNull(BenchLabel.problem("lsb3_r1_s3_a03_genuine_L2_0412"))
        assertNotNull(BenchLabel.problem("lsb3_r1_s03_a03_photo_L2_0412")) // no instrument
        assertNotNull(BenchLabel.problem("lsb3_r1_s03_a03_printed-photo_L2_0412")) // wrong species prefix
        assertNotNull(BenchLabel.problem("lsb3_r1_s03_a03_genuine_L4_0412"))
        assertNotNull(BenchLabel.problem("lsb3_r1_s03_a03_genuine_L2_412"))
        assertNotNull(BenchLabel.problem("lsb3_r1_s03_A03_genuine_L2_0412"))
        assertEquals("Run should look like r1", BenchLabel.problem("lsb3_1_s03_a03_genuine_L2_0412"))
    }
}
