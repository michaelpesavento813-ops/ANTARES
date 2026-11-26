package com.antares.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectRulesTest {
    @Test fun touchingOutlinesAreDistinctAndOverlapRequiresManualCorrection() {
        val left = Region(0.05f, 0.12f, 0.5f, 0.8f)
        val right = Region(0.5f, 0.2f, 0.95f, 0.85f)
        assertFalse(left.intersects(right))
        assertEquals(0f, left.overlapRatio(right), 0.0001f)
        assertEquals(1f, left.overlapRatio(left), 0.0001f)
        assertNotEquals(1f, left.overlapRatio(Region(0.06f, 0.12f, 0.5f, 0.8f)))
        assertTrue(isolatedRegion(left, listOf(left, right)))
        val joined = Region(0.1f, 0.1f, 0.9f, 0.9f)
        assertFalse(isolatedRegion(joined, listOf(left, right)))
        assertTrue(isolatedRegion(joined, emptyList()))
    }

    @Test fun workflowRoutesRemainDistinct() {
        assertEquals(listOf("Restorative Repair", "Make Me Pretty", "Deep Scan/Mapping", "Build Composite Image"),
            Workflow.entries.map { it.title })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRegionCannotBeConfirmed() {
        Region(0f, 0f, Float.NaN, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun compositeRequiresDistinctConfirmedReferences() {
        val region = Region(0f, 0f, 1f, 1f)
        val reference = SubjectReference(21, region)
        WorkflowRequest(Workflow.COMPOSITE, listOf(reference, reference), 0, 45)
    }
}
