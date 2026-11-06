package com.tajchert.trefoil.core.protocol
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class EffectRpcTest {
 @Test fun requestUsesEffectEnvelope() {
   val value = EffectRpc.request("7", "orchestration.subscribeShell", buildJsonObject {})
   assertEquals("Request", value.string("_tag")); assertEquals("7", value.string("id"))
   assertEquals(JsonArray(emptyList()), value["headers"]); assertFalse(value.containsKey("jsonrpc"))
 }
 @Test fun handlesBatchedChunkAndUnknownFields() {
   val frames = EffectRpc.decode("""[{"_tag":"Chunk","requestId":"7","values":[{"kind":"synchronized"}],"future":true},{"_tag":"Pong"}]""")
   assertEquals(2, frames.size); assertEquals("synchronized", frames[0].objects("values")[0].string("kind"))
 }
 @Test(expected = IllegalStateException::class) fun rejectsPrimitiveFrame() { EffectRpc.decode("1") }
}
