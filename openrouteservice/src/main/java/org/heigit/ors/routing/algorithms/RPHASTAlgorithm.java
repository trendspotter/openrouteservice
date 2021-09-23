/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1 
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library; 
 *  if not, see <https://www.gnu.org/licenses/>.  
 */
package org.heigit.ors.routing.algorithms;

import java.util.PriorityQueue;

import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.coll.GHIntObjectHashMap;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.CHGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;

import org.heigit.ors.routing.graphhopper.extensions.edgefilters.ch.DownwardSearchEdgeFilter;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.ch.UpwardSearchEdgeFilter;
import org.heigit.ors.routing.graphhopper.extensions.storages.MultiTreeSPEntry;
import org.heigit.ors.routing.graphhopper.extensions.storages.MultiTreeSPEntryItem;

public class RPHASTAlgorithm extends AbstractManyToManyRoutingAlgorithm {
	private IntObjectMap<MultiTreeSPEntry> bestWeightMapFrom;
	private MultiTreeSPEntry currFrom;
	private PriorityQueue<MultiTreeSPEntry> prioQueue;
	private final UpwardSearchEdgeFilter upwardEdgeFilter;
	private final DownwardSearchEdgeFilter downwardEdgeFilter;
	private SubGraph targetGraph;
	private boolean finishedFrom;
	private boolean finishedTo;
	private int visitedCountFrom;
	private int visitedCountTo;
	private int treeEntrySize;
	
	private MultiTreeSPEntryItem msptItem;
	private boolean addToQueue = false;
	private double edgeWeight;
	private double entryWeight;
	private double tmpWeight;
	
	public RPHASTAlgorithm(Graph graph, Weighting weighting, TraversalMode traversalMode) {
		super(graph, weighting, traversalMode);

		int size = Math.min(Math.max(200, graph.getNodes() / 10), 2000);

		initCollections(size);

		CHGraph chGraph = null;
		if (graph instanceof CHGraph)
			chGraph = (CHGraph) graph;
		else if (graph instanceof QueryGraph) {
			QueryGraph qGraph = (QueryGraph) graph;
			chGraph = (CHGraph) qGraph.getBaseGraph();
		}

		setMaxVisitedNodes(Integer.MAX_VALUE);
		FlagEncoder encoder = weighting.getFlagEncoder();

		upwardEdgeFilter = new UpwardSearchEdgeFilter(chGraph, encoder);
		downwardEdgeFilter = new DownwardSearchEdgeFilter(chGraph, encoder);

		inEdgeExplorer = graph.createEdgeExplorer();
		outEdgeExplorer = graph.createEdgeExplorer();
	}

	protected void initCollections(int size) {
		prioQueue = new PriorityQueue<>(size);
		bestWeightMapFrom = new GHIntObjectHashMap<>(size);
	}

	@Override
	public void reset() {
		finishedFrom = false;
		finishedTo = false;
		prioQueue.clear();
		bestWeightMapFrom.clear();
	}

	@Override
	public void prepare(int[] sources, int[] targets) {
		PriorityQueue<Integer> localPrioQueue = new PriorityQueue<>(100);
		treeEntrySize = sources.length;

		// Phase I: build shortest path tree from all target nodes to the
		// highest node
		targetGraph = new SubGraph(graph);

		addNodes(targetGraph, localPrioQueue, targets);

		while (!localPrioQueue.isEmpty()) {
			int adjNode = localPrioQueue.poll();
			EdgeIterator iter = outEdgeExplorer.setBaseNode(adjNode);
			downwardEdgeFilter.setBaseNode(adjNode);

			while (iter.next()) {
				if (!downwardEdgeFilter.accept(iter))
					continue;

				if (targetGraph.addEdge(adjNode, iter, true))
					localPrioQueue.add(iter.getAdjNode());
			}
		}
	}

	private void addNodes(SubGraph graph, PriorityQueue<Integer> prioQueue, int[] nodes) {
		for (int i = 0; i < nodes.length; i++) {
			int nodeId = nodes[i];
			if (nodeId >= 0) {
				if (graph != null)
					graph.addEdge(nodeId, null, true);
				prioQueue.add(nodeId);
			}
		}
	}

	protected void runUpwardSearch() {
		while (!isMaxVisitedNodesExceeded() && !finishedFrom) {
			finishedFrom = !upwardSearch();
		}
	}

	protected void runDownwardSearch() {
		while (!finishedTo) {
			finishedTo = !downwardSearch();
		}
	}

	@Override
	public int getVisitedNodes() {
		return visitedCountFrom + visitedCountTo;
	}

	private boolean upwardSearch() {
		if (prioQueue.isEmpty())
			return false;

		currFrom = prioQueue.poll();
		fillEdgesUpward(currFrom, prioQueue, bestWeightMapFrom, outEdgeExplorer);
		visitedCountFrom++;

		return true;
	}

	private boolean downwardSearch() {
		if (prioQueue.isEmpty())
			return false;

		MultiTreeSPEntry currTo = prioQueue.poll();
		fillEdgesDownward(currTo, prioQueue, bestWeightMapFrom, outEdgeExplorer);
		visitedCountTo++;

		return true;
	}

	@Override
	public MultiTreeSPEntry[] calcPaths(int[] from, int[] to) {
		for (int i = 0; i < from.length; i++) {
			if (from[i] == -1)
			  continue;

			//If two queried points are on the same node, this case can occur
			MultiTreeSPEntry existing = bestWeightMapFrom.get(from[i]);
			if (existing != null) {
				existing.getItem(i).setWeight(0.0);
				continue;
			}
			
			currFrom = new MultiTreeSPEntry(from[i], EdgeIterator.NO_EDGE, 0.0, true, null, from.length);
			currFrom.getItem(i).setWeight(0.0);
			currFrom.setVisited(true);
				prioQueue.add(currFrom);

			if (!traversalMode.isEdgeBased())
				bestWeightMapFrom.put(from[i], currFrom);
			else
				throw new IllegalStateException("Edge-based behavior not supported");
		}
 
		outEdgeExplorer = graph.createEdgeExplorer();

		runUpwardSearch();

		currFrom = bestWeightMapFrom.get(upwardEdgeFilter.getHighestNode());
		currFrom.setVisited(true);
		currFrom.resetUpdate(true);
		prioQueue.clear();
		prioQueue.add(currFrom);

		for (int i = 0; i < from.length; i++) {
			int sourceNode = from[i];
			MultiTreeSPEntry mspTree = bestWeightMapFrom.get(sourceNode);
			mspTree.getItem(i).setUpdate(true);
			prioQueue.add(mspTree);
		}

		outEdgeExplorer = targetGraph.createExplorer();
		runDownwardSearch();

		MultiTreeSPEntry[] targets = new MultiTreeSPEntry[to.length];

		for (int i = 0; i < to.length; ++i)
			targets[i] = bestWeightMapFrom.get(to[i]);

		return targets;
	}

	private void fillEdgesUpward(MultiTreeSPEntry currEdge, PriorityQueue<MultiTreeSPEntry> prioQueue,
			IntObjectMap<MultiTreeSPEntry> shortestWeightMap, EdgeExplorer explorer) {
		EdgeIterator iter = explorer.setBaseNode(currEdge.getAdjNode());

		if (iter == null) // we reach one of the target nodes
			return;

		upwardEdgeFilter.setBaseNode(currEdge.getAdjNode());

		while (iter.next()) {
			if (!upwardEdgeFilter.accept(iter))
				continue;
			
			upwardEdgeFilter.updateHighestNode(iter);
			
			edgeWeight = weighting.calcEdgeWeight(iter, false, 0);

			if (!Double.isInfinite(edgeWeight)) {
				MultiTreeSPEntry ee = shortestWeightMap.get(iter.getAdjNode());

				if (ee == null) {
					ee = new MultiTreeSPEntry(iter.getAdjNode(), iter.getEdge(), edgeWeight, true, currEdge, currEdge.getSize());

					shortestWeightMap.put(iter.getAdjNode(), ee);
					prioQueue.add(ee);
				} else {
					addToQueue = false;
					
					for (int i = 0; i < treeEntrySize; ++i) {
						msptItem = currEdge.getItem(i);
						entryWeight = msptItem.getWeight();
						
						if (entryWeight == Double.POSITIVE_INFINITY || !msptItem.isUpdate())
							continue;

						MultiTreeSPEntryItem msptSubItem = ee.getItem(i);
						
						tmpWeight = edgeWeight + entryWeight;

						if (msptSubItem.getWeight() > tmpWeight) {
							msptSubItem.setWeight(tmpWeight);
							msptSubItem.setEdge(iter.getEdge());
							msptSubItem.setParent(currEdge);
							msptSubItem.setUpdate(true);
							addToQueue = true;
						}
					}

					if (addToQueue) {
						ee.updateWeights();
						prioQueue.remove(ee);
						prioQueue.add(ee);
					}
				}
			}
		}
		
		if(!targetGraph.containsNode(currEdge.getAdjNode())) currEdge.resetUpdate(false);
	}

	private void fillEdgesDownward(MultiTreeSPEntry currEdge, PriorityQueue<MultiTreeSPEntry> prioQueue,
			IntObjectMap<MultiTreeSPEntry> shortestWeightMap, EdgeExplorer explorer) {

		EdgeIterator iter = explorer.setBaseNode(currEdge.getAdjNode());

		if (iter == null)
			return;

		while (iter.next()) {
			edgeWeight = weighting.calcEdgeWeight(iter, false, 0);

			if (!Double.isInfinite(edgeWeight)) {
				MultiTreeSPEntry ee = shortestWeightMap.get(iter.getAdjNode());

				if (ee == null) {
					ee = new MultiTreeSPEntry(iter.getAdjNode(), iter.getEdge(), edgeWeight, true, currEdge, currEdge.getSize());
					ee.setVisited(true);

					shortestWeightMap.put(iter.getAdjNode(), ee);
					prioQueue.add(ee);
				} else {
					addToQueue = false;
					
					for (int i = 0; i < treeEntrySize; ++i) {
						msptItem = currEdge.getItem(i);
						entryWeight = msptItem.getWeight();
						
						if (entryWeight == Double.POSITIVE_INFINITY)
							continue;

						tmpWeight = edgeWeight + entryWeight;

						MultiTreeSPEntryItem eeItem = ee.getItem(i);

						if (eeItem.getWeight() > tmpWeight) {
							eeItem.setWeight(tmpWeight);
							eeItem.setEdge(iter.getEdge());
							eeItem.setParent(currEdge);
							eeItem.setUpdate(true);
							addToQueue = true;
						}
					}
					
					ee.updateWeights();
					
					if (!ee.isVisited()) {
						// // This is the case if the node has been assigned a
						// weight in
						// // the upwards pass (fillEdges). We need to use it in
						// the
						// // downwards pass to access lower level nodes, though
						// the
						// weight
						// // does not have to be reset necessarily //
						ee.setVisited(true);
						prioQueue.add(ee);
					} else if (addToQueue) {
						ee.setVisited(true);
						prioQueue.remove(ee);
						prioQueue.add(ee);
					}
				}
			}
		}
	}
}
