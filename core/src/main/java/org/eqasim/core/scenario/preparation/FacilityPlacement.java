package org.eqasim.core.scenario.preparation;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.misc.ParallelProgress;
import org.eqasim.core.scenario.cutter.network.RoadNetwork;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.collections.QuadTrees;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityFacilityImpl;

/**
 * Attaches every facility to a link of the road (car) network.
 *
 * <p>
 * A facility whose input file already carries a link keeps it (TUBS-IVS/eqasim-bs#465, ADR-0167): the regional
 * pipeline pins the facilities at the cordon road gates to a motorway or trunk carriageway, which the
 * {@link FacilityPlacementVoter} would never choose. A preset link must be a car link of the network, otherwise
 * the placement fails before any facility is placed. Every other facility gets the closest link the voter accepts.
 * The numbers of kept and placed facilities are logged.
 * </p>
 */
public class FacilityPlacement {
	private final static Logger logger = LogManager.getLogger(FacilityPlacement.class);

	private final int numberOfThreads;
	private final int batchSize;
	private final RoadNetwork network;
	private final QuadTree<Link> spatialIndex;

	private int numberOfPresetLinks = 0;
	private int numberOfPlacedFacilities = 0;

	public FacilityPlacement(int numberOfThreads, int batchSize, RoadNetwork network, FacilityPlacementVoter voter) {
		this.numberOfThreads = numberOfThreads;
		this.batchSize = batchSize;
		this.network = network;

		this.spatialIndex = QuadTrees.createQuadTree(
				network.getLinks().values().stream().filter(voter::canPlaceFacility).collect(Collectors.toList()));
	}

	public void run(ActivityFacilities facilities) throws InterruptedException {
		// Validate the preset links before the worker threads start: an exception inside a worker would only end
		// that thread and leave facilities without a link.
		numberOfPresetLinks = 0;

		for (ActivityFacility facility : facilities.getFacilities().values()) {
			if (facility.getLinkId() != null) {
				if (!network.getLinks().containsKey(facility.getLinkId())) {
					throw new IllegalStateException(String.format(
							"Facility %s carries the preset link %s, which is not a car link of the network",
							facility.getId(), facility.getLinkId()));
				}

				numberOfPresetLinks++;
			}
		}

		numberOfPlacedFacilities = facilities.getFacilities().size() - numberOfPresetLinks;

		Iterator<? extends ActivityFacility> facilityIterator = facilities.getFacilities().values().iterator();

		List<Thread> threads = new LinkedList<>();

		ParallelProgress progress = new ParallelProgress("Assigning links to facilities ...",
				facilities.getFacilities().size());

		for (int i = 0; i < numberOfThreads; i++) {
			Thread thread = new Thread(new Worker(facilityIterator, progress));
			threads.add(thread);
		}

		threads.forEach(Thread::start);
		progress.start();

		for (Thread thread : threads) {
			thread.join();
		}

		progress.close();

		logger.info(String.format("Facility placement: %d of %d facilities kept their preset link, %d were placed"
				+ " on the closest eligible link", numberOfPresetLinks, facilities.getFacilities().size(),
				numberOfPlacedFacilities));
	}

	public int getNumberOfPresetLinks() {
		return numberOfPresetLinks;
	}

	public int getNumberOfPlacedFacilities() {
		return numberOfPlacedFacilities;
	}

	private class Worker implements Runnable {
		private final Iterator<? extends ActivityFacility> iterator;
		private final ParallelProgress progress;

		Worker(Iterator<? extends ActivityFacility> iterator, ParallelProgress progress) {
			this.iterator = iterator;
			this.progress = progress;
		}

		@Override
		public void run() {
			List<ActivityFacility> localTasks = new LinkedList<>();

			do {
				localTasks.clear();

				synchronized (iterator) {
					while (iterator.hasNext() && localTasks.size() < batchSize) {
						localTasks.add(iterator.next());
					}
				}

				for (ActivityFacility facility : localTasks) {
					if (facility.getLinkId() != null) {
						continue; // preset link, validated in run()
					}

					Link link = spatialIndex.getClosest(facility.getCoord().getX(), facility.getCoord().getY());
					((ActivityFacilityImpl) facility).setLinkId(link.getId());
				}

				progress.update(localTasks.size());
			} while (localTasks.size() > 0);
		}
	}

	static public interface FacilityPlacementVoter {
		boolean canPlaceFacility(Link link);
	}

	static public class OSMFacilityPlacementVoter implements FacilityPlacementVoter {
		private final static String HIGHWAY_TAG = "osm:way:highway";
		
		public OSMFacilityPlacementVoter(RoadNetwork network) {
			boolean foundAttribute = false;

			for (Link link : network.getLinks().values()) {
				if (link.getAttributes().getAttribute(HIGHWAY_TAG) != null) {
					foundAttribute = true;
					break;
				}
			}

			if (!foundAttribute) {
				throw new IllegalStateException("Did not find osm:highway attribute in network");
			}
		}

		@Override
		public boolean canPlaceFacility(Link link) {
			String highway = (String) link.getAttributes().getAttribute(HIGHWAY_TAG);

			if (highway != null) {
				if (highway.contains("motorway")) {
					return false;
				}

				if (highway.contains("trunk")) {
					return false;
				}
				
				if (highway.contains("_link")) {
					return false;
				}
			}

			return true;
		}
	}
}
