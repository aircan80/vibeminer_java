# Table exploration design alternatives

Open [the comparison page](index.html) locally in a browser. These are three interactive HTML design alternatives for review under [issue #107](https://github.com/aircan80/vibeminer/issues/107), not a production table browser or an approved implementation choice.

- [A · Explorer](explorer/index.html): table list, record grid and persistent inspector.
- [B · Workbench](workbench/index.html): wide grid, multiple table tabs and a schema/field drawer.
- [C · Trail](trail/index.html): connected-record exploration with the source relationship kept in view.

The scope is raw tables: discovering table names, browsing records, understanding column names/types and following declared relationships within the same exact build. Higher-level Items, Spells and other domain-specific views remain outside this design exercise.

Each prototype uses authored example rows. The build header provides context rather than a claim that those rows were extracted from that build. See [primary-source research](RESEARCH.md) for the pinned definitions, field semantics and limitations. In particular, a declared foreign key can target a non-ID column; unannotated numeric fields and arrays must not become guessed relationships. Missing schemas, missing records, unavailable table content and multiple matches need distinct behavior.

Review at desktop widths, try table/record search, select rows, inspect schema/types, follow linked values and return using history. State controls in the prototypes are review tools for otherwise difficult-to-reach conditions. Navigation and metadata presentation are illustrative; these documents do not establish production performance or accessibility certification.

## Review evidence

All three prototypes were rendered in Firefox at a 1440 × 1000 outer window, checked for visible records and page overflow, and visually reviewed. Local asset/navigation links resolve. Independent usability review covered exact target columns, originating-record preservation, Back/Forward filter and sort restoration, ambiguity handling and keyboard focus; corrections were applied before delivery. Additional interactive browser checks covered the Workbench and navigation checks covered Trail. These checks qualify the prototypes for design review, not a production JavaFX implementation.

- [Explorer screenshot](screenshots/explorer.png)
- [Workbench screenshot](screenshots/workbench.png)
- [Trail screenshot](screenshots/trail.png)

Explorer refinement ([#108](https://github.com/aircan80/vibeminer/issues/108)): the desktop inspector is 340 px wide. Selecting a table in the left catalogue starts a fresh breadcrumb/history; following linked fields extends that trail. Option A is the current preference, pending further review.

Navigation refinement ([#110](https://github.com/aircan80/vibeminer/issues/110)): Explorer retains the application sidebar with Tables selected. Its table catalogue is a separate inner pane; the header and table breadcrumb trail keep their existing responsibilities.
