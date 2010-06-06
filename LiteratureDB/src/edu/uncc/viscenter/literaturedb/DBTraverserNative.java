package edu.uncc.viscenter.literaturedb;

import java.util.Vector;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.TraversalPosition;
import org.neo4j.graphdb.Traverser;
import org.neo4j.index.IndexHits;
import org.neo4j.index.IndexService;
import org.neo4j.index.lucene.LuceneFulltextQueryIndexService;
import org.neo4j.index.lucene.LuceneIndexService;
import org.neo4j.kernel.EmbeddedGraphDatabase;

public class DBTraverserNative {

	private static final String DB_PATH = "db/litdbDOM";

	private enum LitDBRels implements RelationshipType {
		ARTICLES, ARTICLE, AUTHORS, AUTHOR, WROTE, WRITTEN_BY, CITES
	};

	public static void main(String[] args) {

		// getting database and index running
		GraphDatabaseService db = new EmbeddedGraphDatabase(DB_PATH);
		IndexService index = new LuceneIndexService(db);
		LuceneFulltextQueryIndexService indexFullQuery = new LuceneFulltextQueryIndexService(
				db);
		registerShutdownHook(db, index, indexFullQuery);

		topWriters(db);

		listCoAuthors(db, indexFullQuery, "\"Ben Shneiderman\"");

		// wrapping up...
		db.shutdown();
		index.shutdown();
	}

	private static void listCoAuthors(GraphDatabaseService db,
			LuceneFulltextQueryIndexService indexFullQuery, String nameQuery) {
		Transaction tx = db.beginTx();
		try {

			// because there are dups, picking up multiple nodes for the same
			// name queried
			IndexHits<Node> dbAuthorsFound = indexFullQuery.getNodes("name",
					nameQuery);

			System.out.println("Co-authors for " + nameQuery + " found:");
			for (Node a : dbAuthorsFound) {
				Traverser traverser = a.traverse(Traverser.Order.DEPTH_FIRST,
						StopEvaluator.END_OF_GRAPH, new ReturnableEvaluator() {
							public boolean isReturnableNode(
									TraversalPosition pos) {
								return !pos.isStartNode()
										&& pos.lastRelationshipTraversed()
												.isType(LitDBRels.WROTE)
										&& pos.currentNode().hasRelationship(
												LitDBRels.AUTHOR)
										// modify this to restrict/relax
										// co-authorship scan.
										&& pos.depth() <= 2;
							}
						}, LitDBRels.WROTE, Direction.OUTGOING,
						LitDBRels.WROTE, Direction.INCOMING);
				for (Node ca : traverser) {

					// 'hop' here represents a hop from author to co-author.
					System.out.println("At 'hop' "
					/*
					 * for each hop, need to follow two links: author->doc and
					 * doc->co-author, so that's why divison by 2.
					 */
					+ traverser.currentPosition().depth() / 2 + ": "
							+ ca.getProperty("name"));
				}
			}

			tx.success();
		} finally {
			tx.finish();
		}
	}

	private static void topWriters(GraphDatabaseService db) {
		// TODO: traverse to get the author who wrote the most articles.
		Transaction tx = db.beginTx();
		try {
			// using sub-reference node, authors.
			Node authors = db.getReferenceNode().getSingleRelationship(
					LitDBRels.AUTHORS, Direction.OUTGOING).getEndNode();

			int maxDocs = 0;

			/* in case there is a tie for the top writer. */
			Vector<Node> topWriters = new Vector<Node>();

			// Get the top writer(s) a la selection sort.
			for (Relationship authorRel : authors.getRelationships(
					LitDBRels.AUTHOR, Direction.OUTGOING)) {
				Node author = authorRel.getEndNode();

				/*
				 * Traverser interface is deprecated in the upcoming neo4j 1.1.
				 */
				Traverser traverser = author.traverse(
						Traverser.Order.BREADTH_FIRST, StopEvaluator.DEPTH_ONE,
						ReturnableEvaluator.ALL_BUT_START_NODE,
						LitDBRels.WROTE, Direction.OUTGOING);

				int docCount = 0;
				for (Node doc : traverser) {
					docCount++;
					// do something sysout with doc here?
				}

				if (docCount == maxDocs) {
					topWriters.add(author);
				} else if (docCount > maxDocs) {
					topWriters.clear();
					topWriters.add(author);
					maxDocs = docCount;
				}
			}

			System.out.println("--- Top Writer Query ----");
			System.out.println("The top author(s) with " + maxDocs
					+ " publications:");
			for (Node a : topWriters) {
				System.out.println(a.getProperty("name"));
			}
			System.out.println("-------------------------");

			tx.success();
		} finally {
			tx.finish();
		}
	}

	private static void registerShutdownHook(
			final GraphDatabaseService graphDb, final IndexService index,
			final LuceneFulltextQueryIndexService indexFullQuery) {
		// Registers a shutdown hook for the Neo4j instance so that it
		// shuts down nicely when the VM exits (even if you "Ctrl-C" the
		// running example before it's completed)
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				graphDb.shutdown();
				index.shutdown();
				indexFullQuery.shutdown();
			}
		});
	}
}
