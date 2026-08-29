package com.trapezo.pos.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Path-classification tests for managed store-logo deletion.
 *
 * The regression guarded here: a decoy directory literally named `store_media` sitting OUTSIDE
 * the app's own filesDir must never be treated as managed storage.
 */
class StoreLogoContainmentTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun root(): File = File(tmp.root, "files/store_media").apply { mkdirs() }.canonicalFile

    @Test fun acceptsDirectManagedChild() {
        val root = root()
        val target = File(root, "store_logo_123_abc.png").canonicalFile
        assertTrue(StoreLogoStorage.isManagedLogo(root, target))
    }

    @Test fun rejectsDecoyStoreMediaDirectoryElsewhere() {
        val root = root()
        // /some/other/place/store_media/store_logo_x.png — passes a name-only check, must fail here.
        val decoy = File(tmp.root, "other/place/store_media").apply { mkdirs() }
        val target = File(decoy, "store_logo_x.png").canonicalFile
        assertFalse(StoreLogoStorage.isManagedLogo(root, target))
    }

    @Test fun rejectsUnmanagedFilenameInsideRoot() {
        val root = root()
        val target = File(root, "not_a_logo.png").canonicalFile
        assertFalse(StoreLogoStorage.isManagedLogo(root, target))
    }

    @Test fun rejectsNestedChildOfRoot() {
        val root = root()
        val nested = File(root, "sub").apply { mkdirs() }
        val target = File(nested, "store_logo_x.png").canonicalFile
        assertFalse(StoreLogoStorage.isManagedLogo(root, target))
    }

    @Test fun rejectsTraversalEscapeOutOfRoot() {
        val root = root()
        // Canonicalization collapses "..", so this resolves outside the managed root.
        val target = File(root, "../../outside/store_logo_x.png").canonicalFile
        assertFalse(StoreLogoStorage.isManagedLogo(root, target))
    }

    @Test fun rejectsRootItself() {
        val root = root()
        assertFalse(StoreLogoStorage.isManagedLogo(root, root))
    }
}
