package com.malaron.atlasstream.streaming;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VirtualTextureResidencyPriorityTest {
	@Test
	void onlyNearerOrFinerRequestsCanReplaceResidentTiles() {
		var priority = new VirtualTextureResidencyPriority();
		var near = key(0, 0);
		var far = key(1, 1);
		priority.record(near, 4);
		priority.record(far, 100);

		assertNull(priority.victimFor(key(0, 2), 101));
		assertNull(priority.victimFor(key(1, 2), 100));
		assertEquals(far, priority.victimFor(key(0, 2), 100));
		assertEquals(far, priority.victimFor(key(2, 2), 25));
	}

	@Test
	void nearestUseWinsWithinEpochAndOldDemandExpires() {
		var priority = new VirtualTextureResidencyPriority();
		var tile = key(0, 0);
		priority.record(tile, 10);
		priority.record(tile, 100);
		assertNull(priority.victimFor(key(0, 1), 50));
		priority.nextEpoch();
		priority.record(tile, 100);
		assertEquals(tile, priority.victimFor(key(0, 1), 50));
		priority.nextEpoch();
		assertNull(priority.victimFor(key(0, 1), 150));
		priority.nextEpoch();
		assertEquals(tile, priority.victimFor(key(0, 1), 150));
		priority.remove(tile);
		assertNull(priority.victimFor(key(0, 1), 0));
	}

	@Test
	void repeatedLowerPriorityRefreshCannotThrashAFullCache() {
		var priority = new VirtualTextureResidencyPriority();
		var resident = key(0, 0);
		var other = key(0, 1);
		priority.record(resident, 10);
		for (int cycle = 0; cycle < 10; cycle++) {
			priority.nextEpoch();
			priority.record(resident, 10);
			assertNull(priority.victimFor(other, 100));
		}
	}

	@Test
	void overCapacityRefreshConvergesAndAllowsMovementToReplaceStaleTiles() {
		var priority = new VirtualTextureResidencyPriority();
		Set<VirtualTextureTileKey> resident = new HashSet<>();
		int evictions = 0;
		for (int cycle = 0; cycle < 5; cycle++) {
			priority.nextEpoch();
			int cycleEvictions = 0;
			for (int i = 999; i >= 0; i--) {
				var key = key(0, i);
				if (resident.contains(key)) {
					priority.record(key, i);
				} else if (resident.size() < 100) {
					resident.add(key);
					priority.record(key, i);
				} else {
					var victim = priority.victimFor(key, i);
					if (victim != null) {
						resident.remove(victim);
						priority.remove(victim);
						resident.add(key);
						priority.record(key, i);
						cycleEvictions++;
					}
				}
			}
			evictions += cycleEvictions;
			if (cycle > 0) {
				assertEquals(0, cycleEvictions);
			}
		}
		assertEquals(900, evictions);
		assertEquals(100, resident.size());
		priority.nextEpoch();
		priority.nextEpoch();
		assertNotNull(priority.victimFor(key(0, 1000), 0));
	}

	private static VirtualTextureTileKey key(int mip, int x) {
		return new VirtualTextureTileKey(1, mip, x, 0);
	}
}
