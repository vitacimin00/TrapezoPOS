package com.trapezo.pos.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Path-classification tests for managed product-photo deletion.
 *
 * The regression guarded here: a decoy directory literally named `product_photos` sitting OUTSIDE
 * the app's own filesDir must never be treated as managed storage. Mirrors
 * [StoreLogoContainmentTest].
 */
class PhotoContainmentTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun root(): File = File(tmp.root, "files/product_photos").apply { mkdirs() }.canonicalFile

    @Test fun acceptsDirectManagedChild() {
        val root = root()
        val target = File(root, "product_123_abc.jpg").canonicalFile
        assertTrue(PhotoStorage.isManagedPhoto(root, target))
    }

    @Test fun rejectsDecoyProductPhotosDirectoryElsewhere() {
        val root = root()
        // /some/other/location/product_photos/product_x.jpg — passes a name-only check.
        val decoy = File(tmp.root, "other/location/product_photos").apply { mkdirs() }
        val target = File(decoy, "product_x.jpg").canonicalFile
        assertFalse(PhotoStorage.isManagedPhoto(root, target))
    }

    @Test fun rejectsWrongPrefixInsideRoot() {
        val root = root()
        val target = File(root, "not_a_photo.jpg").canonicalFile
        assertFalse(PhotoStorage.isManagedPhoto(root, target))
    }

    @Test fun rejectsNestedChildOfRoot() {
        val root = root()
        val nested = File(root, "sub").apply { mkdirs() }
        val target = File(nested, "product_x.jpg").canonicalFile
        assertFalse(PhotoStorage.isManagedPhoto(root, target))
    }

    @Test fun rejectsTraversalEscapeOutOfRoot() {
        val root = root()
        // Canonicalization collapses "..", so this resolves outside the managed root.
        val target = File(root, "../../outside/product_x.jpg").canonicalFile
        assertFalse(PhotoStorage.isManagedPhoto(root, target))
    }

    @Test fun rejectsRootItself() {
        val root = root()
        assertFalse(PhotoStorage.isManagedPhoto(root, root))
    }

    @Test fun rejectsSiblingDirectoryWithSharedPrefix() {
        val root = root()
        // "…/files/product_photos_evil/product_x.jpg" must not be treated as the managed root.
        val sibling = File(tmp.root, "files/product_photos_evil").apply { mkdirs() }
        val target = File(sibling, "product_x.jpg").canonicalFile
        assertFalse(PhotoStorage.isManagedPhoto(root, target))
    }
}
