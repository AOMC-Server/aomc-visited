package io.github.sh4sh.aomcvisited;

/** What an export or upload covers: one record, one dimension's records, or everything recorded. */
record ExportTarget(String dimension, String record) {
	static final ExportTarget EVERYTHING = new ExportTarget(null, null);

	static ExportTarget ofDimension(String dimension) {
		return new ExportTarget(dimension, null);
	}

	static ExportTarget ofRecord(String record) {
		return new ExportTarget(null, record);
	}

	/** For chat, e.g. "the_nether". */
	String description() {
		if (record != null) {
			return VisitedChunks.displayName(record);
		}
		if (dimension != null) {
			return VisitedChunks.shortName(dimension);
		}
		return "everything recorded";
	}

	/** What follows {@code /visited export} or {@code /visited upload} to name this target again. */
	String commandSuffix() {
		if (record != null) {
			return " record " + VisitedChunks.shortName(record);
		}
		if (dimension != null) {
			return " " + VisitedChunks.shortName(dimension);
		}
		return "";
	}
}
