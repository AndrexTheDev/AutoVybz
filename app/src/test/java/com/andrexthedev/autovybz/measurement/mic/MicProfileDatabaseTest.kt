package com.andrexthedev.autovybz.measurement.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gradle-only (needs org.json on the test classpath). Validates the shipped asset. */
class MicProfileDatabaseTest {

    private fun asset(): String {
        val candidates = listOf("src/main/assets/autovybz/mic_profiles.json", "app/src/main/assets/autovybz/mic_profiles.json")
        return candidates.map(::File).first { it.exists() }.readText()
    }

    /** Real-world Build.MANUFACTURER / Build.MODEL pairs -> expected profile id. */
    private val routingCases = listOf(
        // Samsung (model codes)
        Triple("samsung", "SM-S938B", "samsung-galaxy-s25"),
        Triple("Samsung Electronics", "SM-S921B", "samsung-galaxy-s24"),
        Triple("samsung", "SM-G998B", "samsung-galaxy-s21"),
        Triple("samsung", "SM-S721B", "samsung-galaxy-s-fe"),
        Triple("samsung", "SM-G960F", "samsung-galaxy-s-legacy"),
        Triple("samsung", "SM-N986B", "samsung-galaxy-note"),
        Triple("samsung", "SM-F956B", "samsung-galaxy-z-fold"),
        Triple("samsung", "SM-F741B", "samsung-galaxy-z-flip"),
        Triple("samsung", "SM-A556B", "samsung-galaxy-a-upper"),
        Triple("samsung", "SM-A156B", "samsung-galaxy-a"),
        Triple("samsung", "SM-E546B", "samsung-galaxy-m"),
        Triple("samsung", "SM-G736B", "samsung-galaxy-xcover"),
        Triple("samsung", "SM-X710", "samsung-galaxy-tab-s"),
        Triple("samsung", "SM-X200", "samsung-galaxy-tab-a"),
        Triple("samsung", "SM-J730F", "samsung-generic"),
        // Google (marketing names)
        Triple("Google", "Pixel 9 Pro XL", "google-pixel-9"),
        Triple("Google", "Pixel 9 Pro Fold", "google-pixel-fold"),
        Triple("Google", "Pixel 8 Pro", "google-pixel-8"),
        Triple("Google", "Pixel 8a", "google-pixel-8a"),
        Triple("Google", "Pixel 6a", "google-pixel-6a"),
        Triple("Google", "Pixel 5", "google-pixel-legacy"),
        Triple("Google", "Pixel Tablet", "google-pixel-tablet"),
        Triple("Google", "Pixel 10 Pro", "google-pixel-10plus"),
        // Xiaomi family
        Triple("Xiaomi", "Xiaomi 14 Ultra", "xiaomi-flagship"),
        Triple("Xiaomi", "2304FPN6DG", "xiaomi-flagship"),
        Triple("Redmi", "Redmi Note 13 Pro", "xiaomi-redmi-note"),
        Triple("POCO", "POCO F6 Pro", "xiaomi-poco-f"),
        Triple("POCO", "POCO X6 Pro", "xiaomi-poco-xmc"),
        Triple("Xiaomi", "M2101K6G", "xiaomi-redmi-poco"),
        Triple("Xiaomi", "Xiaomi Pad 6", "xiaomi-pad"),
        // BBK: shared CPH code space, separated by manufacturer
        Triple("OnePlus", "NE2213", "oneplus-flagship"),
        Triple("OnePlus", "AC2003", "oneplus-nord"),
        Triple("OnePlus", "CPH2581", "oneplus-cph"),
        Triple("OPPO", "CPH2357", "oppo-cph"),
        Triple("OPPO", "OPPO Find N3", "oppo-find-n"),
        Triple("realme", "RMX3851", "realme-rmx"),
        Triple("realme", "realme GT 6", "realme-gt"),
        Triple("vivo", "vivo X100 Pro", "vivo-x"),
        Triple("vivo", "V2303", "vivo-code"),
        Triple("vivo", "iQOO 12", "iqoo-flagship-vivo"),
        Triple("iQOO", "iQOO Z9 5G", "iqoo-neo-z"),
        // Motorola / Sony / Huawei / HONOR
        Triple("motorola", "motorola edge 50 pro", "motorola-edge"),
        Triple("motorola", "moto g84 5G", "motorola-moto-g"),
        Triple("Sony", "XQ-DQ54", "sony-xperia"),
        Triple("Sony", "H9436", "sony-xperia-legacy"),
        Triple("HUAWEI", "ELS-NX9", "huawei-code"),
        Triple("HUAWEI", "HUAWEI Mate 60 Pro", "huawei-mate"),
        Triple("HONOR", "HONOR Magic6 Pro", "honor-magic"),
        Triple("HONOR", "HONOR X9b", "honor-x"),
        // ASUS / Nothing / Fairphone / Nokia
        Triple("asus", "ASUS_AI2401_C", "asus-code"),
        Triple("Nothing", "A065", "nothing-phone-12"),
        Triple("Nothing", "A015", "nothing-cmf"),
        Triple("Fairphone", "FP5", "fairphone"),
        Triple("HMD Global", "Nokia XR21", "nokia-xr"),
        Triple("HMD Global", "Nokia G42 5G", "nokia-g-c"),
        // Transsion (long manufacturer strings)
        Triple("TECNO MOBILE LIMITED", "TECNO CAMON 30", "tecno-camon"),
        Triple("Infinix Mobility Limited", "Infinix NOTE 40", "infinix-note"),
        Triple("itel", "itel A70", "itel-series"),
        // Tablets / rugged / misc
        Triple("Lenovo", "TB-9707F", "lenovo-tab"),
        Triple("Bullitt Group Limited", "Cat S62 Pro", "cat-phones"),
        Triple("DOOGEE", "DOOGEE S100", "doogee-rugged"),
        Triple("Amazon", "KFTRWI", "amazon-fire"),
        Triple("SHARP", "SH-51D", "sharp-aquos"),
        Triple("SHIFT", "SHIFT6mq", "shift-generic"),
    )

    @Test
    fun shippedDatabaseParsesAndResolvesDevices() {
        val db = MicProfileDatabase.parse(asset())
        assertTrue("expected a broad device database, got ${db.profiles.size}", db.profiles.size >= 150)
        for ((manufacturer, model, expectedId) in routingCases) {
            assertEquals("$manufacturer / $model", expectedId, db.forDevice(manufacturer, model).id)
        }
        assertEquals(db.default.id, db.forDevice("UnknownCorp", "X").id)
        // Every manufacturer in the database has a manufacturer-wide fallback entry,
        // so any unlisted model of a covered brand still resolves to that brand.
        val manufacturers = db.profiles.mapNotNull { it.manufacturer }.distinct()
        for (m in manufacturers) {
            val resolved = db.forDevice(m, "Unlisted Model XYZ 9000")
            assertEquals("$m fallback", m, resolved.manufacturer)
            assertTrue("$m fallback patterns", resolved.modelPatterns.isEmpty())
        }
        for (p in db.profiles) {
            assertTrue(p.id, p.curves.containsKey(InputSource.UNPROCESSED))
            assertEquals(0.0, p.curves.getValue(InputSource.UNPROCESSED).normalisedAt1k().levelAt(1000.0), 1e-9)
        }
    }
}
