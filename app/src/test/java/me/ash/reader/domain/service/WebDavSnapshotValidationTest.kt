package me.ash.reader.domain.service

import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class WebDavSnapshotValidationTest {
    private val service = WebDavSyncService(mock(), mock(), mock(), mock(), AccountOperationLock(), Dispatchers.Unconfined, mock(), mock(), mock())

    @Test fun corruptEmptyAndFutureBackupsAreRejected() {
        listOf("", "null", "{}", "<html>server error</html>", "{\"version\":99,\"groups\":[],\"feeds\":[],\"articles\":[]}", "{\"version\":1,\"groups\":null,\"feeds\":[],\"articles\":[]}")
            .forEach { assertTrue(it, runCatching { service.decodeSnapshot(StringReader(it)) }.isFailure) }
    }

    @Test fun supportedEmptySnapshotCanBeRestored() {
        val data = service.decodeSnapshot(StringReader("{\"version\":1,\"groups\":[],\"feeds\":[],\"articles\":[]}"))
        assertEquals(1, data.version)
        assertTrue(data.articles.isEmpty())
    }
}
