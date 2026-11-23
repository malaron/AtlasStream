package com.malaron.atlasstream.streaming.compact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.malaron.atlasstream.lod.SectionLodPolicy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactPendingQueueTest {
	@Test
	void prioritizesAnApproachingSectionAheadOfAnOlderBacklog() {
		Queue<String> incoming = new ConcurrentLinkedQueue<>();
		Map<String, Integer> sections = new HashMap<>();
		for (int i = 0; i < 80; i++) {
			String sprite = "far" + i;
			sections.put(sprite, 10);
			incoming.add(sprite);
		}
		List<String> sectionSprites = List.of("stone", "grass", "leaves");
		for (String sprite : sectionSprites) {
			sections.put(sprite, 2);
			incoming.add(sprite);
		}
		CompactPendingQueue<String> queue = new CompactPendingQueue<>();
		queue.drainFrom(incoming);
		Set<String> resident = new HashSet<>();
		List<String> completed = new ArrayList<>();

		// One sprite per tick and one block of travel per tick: the section is still eight blocks away after
		// all three of its sprites have been serviced, despite the older far backlog.
		for (int tick = 0; tick < 3; tick++) {
			double cameraX = 22 + tick;
			Comparator<String> order = Comparator.comparingDouble(sprite ->
				SectionLodPolicy.distanceToSection(sections.get(sprite), 0, 0, cameraX, 8, 8));
			queue.prioritiesChanged();
			String next = queue.poll(order);
			resident.add(next);
			if (resident.containsAll(sectionSprites)) {
				completed.addAll(sectionSprites);
			}
		}

		assertEquals(sectionSprites, completed);
		assertEquals(80, queue.size());
		assertEquals(8.0, SectionLodPolicy.distanceToSection(2, 0, 0, 24, 8, 8));
	}

	@Test
	void reordersExistingAndNewRequestsWhenPrioritiesChange() {
		Queue<String> incoming = new ConcurrentLinkedQueue<>(List.of("a", "b"));
		Map<String, Integer> priority = new HashMap<>(Map.of("a", 0, "b", 1, "c", 2));
		Comparator<String> order = Comparator.comparingInt(priority::get);
		CompactPendingQueue<String> queue = new CompactPendingQueue<>();
		queue.drainFrom(incoming);
		assertEquals("a", queue.peek(order));
		priority.put("b", -1);
		queue.prioritiesChanged();
		incoming.add("c");
		queue.drainFrom(incoming);
		assertEquals("b", queue.poll(order));
		assertTrue(queue.contains("c"));
		assertEquals("a", queue.poll(order));
		assertEquals("c", queue.poll(order));
		assertTrue(queue.isEmpty());
		assertFalse(queue.contains("b"));
	}
}
