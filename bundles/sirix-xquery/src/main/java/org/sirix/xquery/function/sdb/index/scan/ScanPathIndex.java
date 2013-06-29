package org.sirix.xquery.function.sdb.index.scan;

import org.brackit.xquery.QueryContext;
import org.brackit.xquery.QueryException;
import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.atomic.Str;
import org.brackit.xquery.function.AbstractFunction;
import org.brackit.xquery.module.StaticContext;
import org.brackit.xquery.sequence.BaseIter;
import org.brackit.xquery.sequence.LazySequence;
import org.brackit.xquery.util.annotation.FunctionAnnotation;
import org.brackit.xquery.xdm.Item;
import org.brackit.xquery.xdm.Iter;
import org.brackit.xquery.xdm.Sequence;
import org.brackit.xquery.xdm.Signature;
import org.brackit.xquery.xdm.Stream;
import org.brackit.xquery.xdm.type.AnyNodeType;
import org.brackit.xquery.xdm.type.AtomicType;
import org.brackit.xquery.xdm.type.Cardinality;
import org.brackit.xquery.xdm.type.SequenceType;
import org.sirix.access.IndexController;
import org.sirix.index.IndexDef;
import org.sirix.index.IndexType;
import org.sirix.index.path.PathFilter;
import org.sirix.xquery.function.FunUtil;
import org.sirix.xquery.function.sdb.SDBFun;
import org.sirix.xquery.node.DBCollection;
import org.sirix.xquery.node.DBNode;
import org.sirix.xquery.stream.SirixNodeKeyStream;

/**
 * Scan the path index.
 * 
 * @author Sebastian Baechle
 * @author Johannes Lichtenberger
 * 
 */
@FunctionAnnotation(description = "Scans the given path index for matching nodes.", parameters = {
		"$collection", "$document", "$idx-no", "$paths" })
public final class ScanPathIndex extends AbstractFunction {

	/** Default function name. */
	public final static QNm DEFAULT_NAME = new QNm(SDBFun.SDB_NSURI,
			SDBFun.SDB_PREFIX, "scan-path-index");

	/**
	 * Constructor.
	 */
	public ScanPathIndex() {
		super(DEFAULT_NAME, new Signature(new SequenceType(AnyNodeType.ANY_NODE,
				Cardinality.ZeroOrMany), new SequenceType(AtomicType.STR,
				Cardinality.One), new SequenceType(AtomicType.STR, Cardinality.One),
				new SequenceType(AtomicType.INR, Cardinality.One), new SequenceType(
						AtomicType.STR, Cardinality.ZeroOrOne)), true);
	}

	@Override
	public Sequence execute(StaticContext sctx, QueryContext ctx, Sequence[] args)
			throws QueryException {
		final String collection = FunUtil.getString(args, 0, "$collection", null,
				null, true);
		final String document = FunUtil.getString(args, 1, "$document", null, null,
				true);
		final int idx = FunUtil.getInt(args, 2, "$idx-no", -1, null, true);

		final DBCollection col = (DBCollection) ctx.getStore().lookup(collection);

		if (col == null) {
			throw new QueryException(new QNm("No valid arguments specified!"));
		}

		IndexController controller = null;
		final Iter docs = col.iterate();
		DBNode doc = (DBNode) docs.next();

		try {
			while (doc != null) {
				if (doc.getTrx().getSession().getResourceConfig().getResource()
						.getName().equals(document)) {
					controller = doc.getTrx().getSession().getIndexController();
					break;
				}
				doc = (DBNode) docs.next();
			}
		} finally {
			docs.close();
		}

		if (controller == null) {
			throw new QueryException(new QNm("Document not found: "
					+ ((Str) args[1]).stringValue()));
		}

		final IndexDef indexDef = controller.getIndexes().getIndexDef(idx, IndexType.PATH);

		if (indexDef == null) {
			throw new QueryException(SDBFun.ERR_INDEX_NOT_FOUND,
					"Index no %s for collection %s and document %s not found.", idx,
					collection, document);
		}
		if (indexDef.getType() != IndexType.PATH) {
			throw new QueryException(SDBFun.ERR_INVALID_INDEX_TYPE,
					"Index no %s for collection %s and document %s is not a path index.",
					idx, collection, document);
		}
		final String paths = FunUtil
				.getString(args, 3, "$paths", null, null, false);
		final PathFilter filter = (paths != null) ? controller.createPathFilter(
				paths.split(";"), doc.getTrx()) : null;

		final IndexController ic = controller;
		final DBNode node = doc;

		return new LazySequence() {
			@Override
			public Iter iterate() {
				return new BaseIter() {
					Stream<?> s;

					@Override
					public Item next() throws QueryException {
						if (s == null) {
							s = new SirixNodeKeyStream(ic.openPathIndex(node.getTrx()
									.getPageTrx(), indexDef, filter), node.getCollection(),
									node.getTrx());
						}
						return (Item) s.next();
					}

					@Override
					public void close() {
						if (s != null) {
							s.close();
						}
					}
				};
			}
		};
	}
}
