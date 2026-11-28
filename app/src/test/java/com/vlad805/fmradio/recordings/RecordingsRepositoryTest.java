package com.vlad805.fmradio.recordings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RecordingsRepositoryTest {
    private static RecordingItem item(
            final String dir,
            final String name,
            final long modifiedMs
    ) {
        final String ext = RecordingsRepository.extractExtension(name);
        final String relPath = dir.isEmpty() ? name : dir + "/" + name;

        return new RecordingItem(
                name,
                dir,
                relPath,
                null,
                "/Music/RFM-Recordings/" + relPath,
                ext,
                RecordingsRepository.mimeTypeForExtension(ext),
                1024L,
                modifiedMs,
                5000L,
                false
        );
    }

    @Test
    public void buildsRootAndNestedDirectoryListingsAndFiltersExtensions() {
        final List<RecordingItem> all = Arrays.asList(
                item("", "root-old.wav", 1000L),
                item("", "root-new.opus", 3000L),
                item("", "legacy.srt", 4000L),
                item("2026-11-25", "a.mp3", 2000L),
                item("2026-11-27", "b.wav", 5000L),
                item("2026-11-27", "c.opus", 6000L),
                item("2026-11-27/dx", "d.mp3", 7000L)
        );

        final DirectoryListing root = RecordingsRepository.buildListingFromEntries(all, "");
        assertEquals("", root.getCurrentRelativeDir());
        assertEquals(Arrays.asList("2026-11-27", "2026-11-25"), root.getSubdirectories());
        assertEquals(3, root.getSubdirectoryCount("2026-11-27"));
        assertEquals(1, root.getSubdirectoryCount("2026-11-25"));
        assertEquals(2, root.getRecordings().size());
        assertEquals("root-new.opus", root.getRecordings().get(0).getDisplayName());
        assertEquals("root-old.wav", root.getRecordings().get(1).getDisplayName());

        final DirectoryListing day = RecordingsRepository.buildListingFromEntries(all, "2026-11-27");
        assertEquals("2026-11-27", day.getCurrentRelativeDir());
        assertEquals(Collections.singletonList("dx"), day.getSubdirectories());
        assertEquals(1, day.getSubdirectoryCount("dx"));
        assertEquals(2, day.getRecordings().size());
        assertEquals("c.opus", day.getRecordings().get(0).getDisplayName());
        assertEquals("b.wav", day.getRecordings().get(1).getDisplayName());

        final DirectoryListing leaf = RecordingsRepository.buildListingFromEntries(all, "2026-11-27/dx");
        assertTrue(leaf.getSubdirectories().isEmpty());
        assertEquals(1, leaf.getRecordings().size());
        assertEquals("d.mp3", leaf.getRecordings().get(0).getDisplayName());
    }

    @Test
    public void normalizesPathsAndParentChildNavigation() {
        assertEquals("", RecordingsRepository.normalizeRelativeDir("/"));
        assertEquals("2026/11", RecordingsRepository.normalizeRelativeDir("/2026/11/"));
        assertEquals("2026-11-27", RecordingsRepository.stripMediaStoreRoot("Music/RFM-Recordings/2026-11-27/"));
        assertEquals("", RecordingsRepository.stripMediaStoreRoot("Music/RFM-Recordings/"));

        assertEquals("2026-11-27", RecordingsRepository.childRelativeDir("", "2026-11-27"));
        assertEquals("2026-11-27/dx", RecordingsRepository.childRelativeDir("2026-11-27", "dx"));
        assertEquals("2026-11-27", RecordingsRepository.parentRelativeDir("2026-11-27/dx"));
        assertEquals("", RecordingsRepository.parentRelativeDir("2026-11-27"));
        assertEquals("", RecordingsRepository.parentRelativeDir(""));

        assertTrue(RecordingsRepository.isSupportedAudioExtension("WAV"));
        assertTrue(RecordingsRepository.isSupportedAudioExtension("mp3"));
        assertTrue(RecordingsRepository.isSupportedAudioExtension("opus"));
        assertFalse(RecordingsRepository.isSupportedAudioExtension("srt"));
    }

    @Test
    public void sortsFilesByRequestedOrderWhileKeepingDirectoriesNewestFirst() {
        final List<RecordingItem> all = Arrays.asList(
                item("2026-11-27", "beta.wav", 3000L),
                item("2026-11-27", "alpha.mp3", 1000L),
                item("2026-11-27", "gamma.opus", 2000L)
        );

        final DirectoryListing oldest = RecordingsRepository.buildListingFromEntries(
                all, "2026-11-27", RecordingsRepository.SORT_OLDEST_FIRST
        );
        assertEquals("alpha.mp3", oldest.getRecordings().get(0).getDisplayName());
        assertEquals("gamma.opus", oldest.getRecordings().get(1).getDisplayName());
        assertEquals("beta.wav", oldest.getRecordings().get(2).getDisplayName());

        final DirectoryListing nameAsc = RecordingsRepository.buildListingFromEntries(
                all, "2026-11-27", RecordingsRepository.SORT_NAME_ASC
        );
        assertEquals("alpha.mp3", nameAsc.getRecordings().get(0).getDisplayName());
        assertEquals("beta.wav", nameAsc.getRecordings().get(1).getDisplayName());
        assertEquals("gamma.opus", nameAsc.getRecordings().get(2).getDisplayName());

        final DirectoryListing nameDesc = RecordingsRepository.buildListingFromEntries(
                all, "2026-11-27", RecordingsRepository.SORT_NAME_DESC
        );
        assertEquals("gamma.opus", nameDesc.getRecordings().get(0).getDisplayName());
        assertEquals("beta.wav", nameDesc.getRecordings().get(1).getDisplayName());
        assertEquals("alpha.mp3", nameDesc.getRecordings().get(2).getDisplayName());
    }
}
