package edu.uncc.viscenter.literaturedb;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.index.IndexService;
import org.neo4j.index.lucene.LuceneFulltextQueryIndexService;
import org.neo4j.index.lucene.LuceneIndexService;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class DBImporterDOM {

	private enum LitDBRels implements RelationshipType {
		ARTICLES, ARTICLE, AUTHORS, AUTHOR, WROTE, WRITTEN_BY, CITES
	}

	private static final String DB_PATH = "db/litdbDOM";

	private static final String P_AUTHOR_ID = "authorID";
	private static final String P_TITLE = "title";
	private static final String P_ID = "articleID";
	private static final String P_KEYWORDS = "keywords";
	private static final String P_NAME = "name";
	private static final String P_YEAR = "year";
	private static final String P_ABSTRACT = "abstract";

	public static void main(String[] argv) {
		Document document = null;

		try {
			Process p = Runtime.getRuntime().exec("rm -rf " + DB_PATH);
			p.waitFor();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		/* setting up neo database */
		GraphDatabaseService db = new EmbeddedGraphDatabase(DB_PATH);
		IndexService index = new LuceneIndexService(db);
		LuceneFulltextQueryIndexService indexFullQuery = new LuceneFulltextQueryIndexService(
				db);

		registerShutdownHook(db, index, indexFullQuery);

		/* setting up XML parsing */
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		// factory.setValidating(true);
		// factory.setNamespaceAware(true);
		try {
			DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.parse(new File(argv[0]));
		} catch (SAXParseException spe) {
			// Error generated by the parser
			System.out.println("\n** Parsing error" + ", line "
					+ spe.getLineNumber() + ", uri " + spe.getSystemId());
			System.out.println("   " + spe.getMessage());

			// Use the contained exception, if any
			Exception x = spe;

			if (spe.getException() != null) {
				x = spe.getException();
			}

			x.printStackTrace();
		} catch (SAXException sxe) {
			// Error generated during parsing)
			Exception x = sxe;

			if (sxe.getException() != null) {
				x = sxe.getException();
			}

			x.printStackTrace();
		} catch (ParserConfigurationException pce) {
			// Parser with specified options can't be built
			pce.printStackTrace();
		} catch (IOException ioe) {
			// I/O error
			ioe.printStackTrace();
		}

		// dropping all nodes and relationship prior to import
		Transaction tx = db.beginTx();
		try {
			for (Node n : db.getAllNodes()) {
				for (Relationship r : n.getRelationships()) {
					r.delete();
				}
				// deleting all but reference node
				if (n.getId() != 0)
					n.delete();
			}
			tx.success();
		} finally {
			tx.finish();
		}

		// First pass through DOM.
		tx = db.beginTx();
		try {

			// Creating reference nodes for all articles and authors for
			// traversals.
			Node db_articles = db.createNode();
			db.getReferenceNode().createRelationshipTo(db_articles,
					LitDBRels.ARTICLES);
			Node db_authors = db.createNode();
			db.getReferenceNode().createRelationshipTo(db_authors,
					LitDBRels.AUTHORS);

			Element articles = document.getDocumentElement();
			NodeList articlesList = articles.getElementsByTagName("article");
			for (int i = 0; i < articlesList.getLength(); i++) {
				Element article = (Element) articlesList.item(i);
				Node db_article = db.createNode();

				db_article.setProperty(P_ID, article.getAttribute("id"));
				db_article.setProperty(P_TITLE, article.getElementsByTagName(
						"title").item(0).getTextContent());

				Element articleDateAttr = (Element) article
						.getElementsByTagName("date").item(0);
				if (articleDateAttr != null) {
					String articleDate = articleDateAttr.getAttribute("from");

					// The year should be the last 4 digits of the date string.
					int l = articleDate.length();
					if (l > 4) {
						db_article.setProperty(P_YEAR, articleDate
								.substring(l - 4));
						// System.out.println("Article year: " +
						// db_article.getProperty(P_YEAR));
					}
				}

				Element keywords = (Element) article.getElementsByTagName(
						"keywords").item(0);
				if (keywords != null)
					db_article.setProperty(P_KEYWORDS, keywords
							.getTextContent());

				// System.out.println(db_article.getProperty(P_TITLE));

				db_articles.createRelationshipTo(db_article, LitDBRels.ARTICLE);
				index.index(db_article, P_ID, db_article.getProperty(P_ID));

				/* processing authors */
				NodeList authors = article.getElementsByTagName("author_ref");
				for (int j = 0; j < authors.getLength(); j++) {
					Element author_ref = (Element) authors.item(j);
					String authorID = author_ref.getAttribute("ref");
					String authorName = author_ref.getTextContent();

					Node db_author = index.getSingleNode(P_AUTHOR_ID, authorID);
					if (db_author == null) {
						db_author = db.createNode();
						db_author.setProperty(P_NAME, authorName);
						db_author.setProperty(P_AUTHOR_ID, authorID);
						index.index(db_author, P_AUTHOR_ID, db_author
								.getProperty(P_AUTHOR_ID));
						indexFullQuery.index(db_author, P_NAME, db_author
								.getProperty(P_NAME));

						db_authors.createRelationshipTo(db_author,
								LitDBRels.AUTHOR);
						// System.out.println("\tNew author: "+authorName);
					} else {
						// System.out.println("\tExisting author: "+authorName);
					}
					db_author.createRelationshipTo(db_article, LitDBRels.WROTE);
					db_article.createRelationshipTo(db_author,
							LitDBRels.WRITTEN_BY);
				}

				// processing document abstract info.
				NodeList pars = article.getElementsByTagName("par");
				StringBuffer documentAbstract = new StringBuffer();
				for (int j = 0; j < pars.getLength(); j++) {
					Element par = (Element) pars.item(j);
					if (j > 0)
						documentAbstract.append("\n");
					documentAbstract.append(par.getTextContent());
				}
				db_article.setProperty(P_ABSTRACT, documentAbstract.toString());
			}

			// Second pass to resolve references
			articlesList = articles.getElementsByTagName("article");
			for (int i = 0; i < articlesList.getLength(); i++) {
				Element article = (Element) articlesList.item(i);

				Node db_article = index.getSingleNode(P_ID, article
						.getAttribute("id"));
				NodeList references = article.getElementsByTagName("ref");
				for (int j = 0; j < references.getLength(); j++) {

					Element ref_article = (Element) references.item(j);
					String refID = ref_article.getAttribute("ref");

					Node db_reference = index.getSingleNode(P_ID, refID);
					if (db_reference != null) {
						if (db_reference.equals(db_article)) {
							System.err.println("SAME? "
									+ db_article.getProperty(P_ID) + " == "
									+ refID);
						} else {
							db_article.createRelationshipTo(db_reference,
									LitDBRels.CITES);
							// System.out.println(db_article.getProperty(P_TITLE)+" -> "+db_reference.getProperty(P_TITLE));
						}
					}
				}

			} // second pass

			tx.success();
		} finally {
			tx.finish();
		}

		db.shutdown();
		index.shutdown();
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
