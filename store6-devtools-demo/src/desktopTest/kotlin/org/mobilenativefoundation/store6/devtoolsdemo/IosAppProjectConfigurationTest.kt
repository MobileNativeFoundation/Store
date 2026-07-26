package org.mobilenativefoundation.store6.devtoolsdemo

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosAppProjectConfigurationTest {
    @Test
    fun targetUsesCommittedInfoPlistWithRequiredComposeBoolean() {
        val projectFile =
            listOf(
                Path.of("iosApp", "iosApp.xcodeproj", "project.pbxproj"),
                Path.of("store6-devtools-demo", "iosApp", "iosApp.xcodeproj", "project.pbxproj"),
            ).firstOrNull(Files::isRegularFile)
        assertNotNull(projectFile, "Could not find the committed iosApp Xcode project")

        val project = Files.readString(projectFile)
        val targetConfigurationList =
            Regex(
                """(?s)[A-F0-9]+ /\* Build configuration list for PBXNativeTarget "iosApp" \*/ = \{\s*""" +
                    """isa = XCConfigurationList;\s*buildConfigurations = \((.*?)\);""",
            ).find(project)?.groupValues?.get(1)
        assertNotNull(targetConfigurationList, "Could not find the iosApp target configuration list")

        val configurations =
            Regex("""([A-F0-9]+) /\* (Debug|Release) \*/""")
                .findAll(targetConfigurationList)
                .associate { match -> match.groupValues[2] to match.groupValues[1] }
        assertEquals(setOf("Debug", "Release"), configurations.keys)

        configurations.forEach { (name, id) ->
            val buildSettings =
                Regex(
                    """(?s)\b$id /\* $name \*/ = \{.*?buildSettings = \{(.*?)""" +
                        """\n\s*};\n\s*name = $name;""",
                ).find(project)?.groupValues?.get(1)
            assertNotNull(buildSettings, "Could not find $name iosApp target build settings")
            assertTrue(
                Regex("""(?m)^\s*GENERATE_INFOPLIST_FILE = NO;\s*$""")
                    .containsMatchIn(buildSettings),
                "$name iosApp target must disable generated Info.plist",
            )
            assertTrue(
                Regex("""(?m)^\s*INFOPLIST_FILE = Info\.plist;\s*$""")
                    .containsMatchIn(buildSettings),
                "$name iosApp target must use the project-root Info.plist",
            )
        }

        val infoPlist = projectFile.parent.parent.resolve("Info.plist")
        assertTrue(Files.isRegularFile(infoPlist), "Could not find the committed project-root Info.plist")
        val infoPlistContents = Files.readString(infoPlist)
        assertTrue(
            Regex(
                """(?s)<key>\s*CADisableMinimumFrameDurationOnPhone\s*</key>\s*<true\s*/>""",
            ).containsMatchIn(infoPlistContents),
            "iosApp/Info.plist must set CADisableMinimumFrameDurationOnPhone to boolean true",
        )
        assertTrue(
            Regex(
                """(?s)<key>\s*UILaunchScreen\s*</key>\s*<dict>\s*""" +
                    """<key>\s*UILaunchScreen\s*</key>\s*<dict\s*/>\s*</dict>""",
            ).containsMatchIn(infoPlistContents),
            "iosApp/Info.plist must preserve the generated launch-screen dictionary",
        )
        assertTrue(
            Regex(
                """(?s)<key>\s*UISupportedInterfaceOrientations~iphone\s*</key>\s*<array>.*?""" +
                    """</array>""",
            ).containsMatchIn(infoPlistContents),
            "iosApp/Info.plist must preserve the iPhone-specific orientations",
        )
    }
}
