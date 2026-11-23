package com.malaron.atlasstream.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualTextureTileRequestQueueTest {
	@Test
	void deduplicatesRequestsAndServesLowerMipLevelsFirst() {
		VirtualTextureTileRequestQueue queue = new VirtualTextureTileRequestQueue(4);
		VirtualTextureTileKey mipTwo = key(2, 0);
		VirtualTextureTileKey firstMipZero = key(0, 0);
		VirtualTextureTileKey secondMipZero = key(0, 1);

		assertTrue(queue.offer(mipTwo));
		assertTrue(queue.offer(firstMipZero));
		assertFalse(queue.offer(firstMipZero));
		assertTrue(queue.offer(secondMipZero));

		assertEquals(firstMipZero, queue.poll());
		assertEquals(secondMipZero, queue.poll());
		assertEquals(mipTwo, queue.poll());
		assertNull(queue.poll());
	}

	@Test
	void evictsLowestPriorityQueuedTileWhenFull() {
		VirtualTextureTileRequestQueue queue = new VirtualTextureTileRequestQueue(2);
		VirtualTextureTileKey coarse = key(3, 0);
		VirtualTextureTileKey medium = key(2, 0);
		VirtualTextureTileKey fine = key(0, 0);

		assertTrue(queue.offer(coarse));
		assertTrue(queue.offer(medium));
		assertTrue(queue.offer(fine));
		assertEquals(2, queue.size());
		assertEquals(fine, queue.poll());
		assertEquals(medium, queue.poll());
		assertNull(queue.poll());
	}

	@Test
	void rejectsRequestsThatCannotImproveAFullQueue() {
		VirtualTextureTileRequestQueue queue = new VirtualTextureTileRequestQueue(1);
		VirtualTextureTileKey mipOne = key(1, 0);
		VirtualTextureTileKey mipTwo = key(2, 0);

		assertTrue(queue.offer(mipOne));
		assertFalse(queue.offer(mipTwo));
		assertEquals(mipOne, queue.poll());
	}

	@Test
	void prioritizesNearbyTilesAcrossMipLevels() {
		VirtualTextureTileRequestQueue queue = new VirtualTextureTileRequestQueue(4);
		VirtualTextureTileKey coarseNearby = key(2, 0);
		VirtualTextureTileKey fineFar = key(0, 1);
		VirtualTextureTileKey fineNear = key(0, 2);

		assertTrue(queue.offer(coarseNearby, 0.0));
		assertTrue(queue.offer(fineFar, 900.0));
		assertTrue(queue.offer(fineNear, 100.0));

		assertEquals(coarseNearby, queue.poll());
		assertEquals(fineNear, queue.poll());
		assertEquals(fineFar, queue.poll());
	}

	@Test
	void nearbyCoarseRequestDisplacesFarFineRequestWhenFull() {
		var queue = new VirtualTextureTileRequestQueue(1);
		var far = key(0, 0);
		var near = key(2, 1);
		assertTrue(queue.offer(far, 900));
		assertTrue(queue.offer(near, 25));
		assertEquals(near, queue.poll());
	}

	@Test
	void duplicateRequestIsUpgradedWhenSeenFromANearerSection() {
		VirtualTextureTileRequestQueue queue = new VirtualTextureTileRequestQueue(2);
		VirtualTextureTileKey nearCandidate = key(0, 0);
		VirtualTextureTileKey other = key(0, 1);

		assertTrue(queue.offer(nearCandidate, 400.0));
		assertTrue(queue.offer(other, 200.0));
		assertTrue(queue.offer(nearCandidate, 25.0));
		assertEquals(2, queue.size());
		assertEquals(nearCandidate, queue.poll());
		assertEquals(other, queue.poll());
	}

	@Test
	void polledRequestPreservesItsUpgradedDistance() {
		var queue = new VirtualTextureTileRequestQueue(2);
		var key = key(0, 0);
		queue.offer(key, 400);
		queue.offer(key, 25);
		assertEquals(new VirtualTextureTileRequestQueue.Request(key, 25), queue.pollRequest());
	}

	private static VirtualTextureTileKey key(int mipLevel, int tileX) {
		return new VirtualTextureTileKey(1, mipLevel, tileX, 0);
	}
}
