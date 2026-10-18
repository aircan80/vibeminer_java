# Atlas visual delivery plan

Formalized Atlas design specification. Screenshots and implementation checks are required before visual acceptance.

## Decisions

Atlas is a JavaFX research workbench, not a browser embedded in JavaFX. Retain warm ivory surfaces, dark ink, muted teal actions, editorial headings, generous whitespace, horizontally organized destinations, and split content/inspector/notebook layouts. Use standard JavaFX controls first; CSS supplies identity, not custom-painted replacements for accessible controls.

A persistent workspace context strip belongs immediately below the navigation: game/edition, channel, region, exact build, locale, availability/coverage. Context must remain visible through search, record relations, assets, notebook and exports. Multiple open workspaces have clearly named tabs; Retail and Forever have separate histories. Display names do not establish protocol identifiers or capability support. Comparisons start within one edition; unvalidated cross-edition comparisons are disabled with an explanation. Shared byte storage does not imply interchangeable records.

Research home starts with six user questions/categories and a scoped search field. Build archive is supporting infrastructure, not the primary home narrative. Source/context in mockup footer moves into a primary visible control group. Provenance details expand progressively without overwhelming record names and useful fields.

## Visual tokens and requirements

- Candidate palette: canvas #F4F2EB, paper #FFFEF9, ink #233A39, teal #27796E, pale teal #E5EEE6; amber for caution, a distinct error color to be reviewed. Mockup muted #78817A and small badges require contrast correction before final tokens. Normal text >=4.5:1; large text and nontext boundaries >=3:1 where applicable.
- Use 4/8 px spacing rhythm; panels 20–24 px padding; 16–24 px gaps; 5–8 px corners. No decorative density at the expense of table space.
- Start at 14 px readable body and 12 px secondary text, with an application text-scale setting. Serif section titles 24–32 px, sans-serif body, monospace IDs/hashes. Select bundled redistributable fonts/license through architect review; do not depend on Inter being installed or Georgia existing on Linux.
- Focus indicator is clearly visible, independent of hover/selection, >=2 px outline with adequate contrast. Never use green/red or strikethrough alone for changes; label Before/After, Added/Modified/Removed and state names explicitly.
- Preserve content at 1280x800, 100% and 200% text scale; inspector can collapse to a labeled drawer. At small width, reduce columns and reveal details rather than truncate identity beyond recovery. Test on Windows, macOS and Linux scaling.

## Screen/component mapping

| Screen | JavaFX foundation | Visual/state requirement |
| --- | --- | --- |
| Shell/context | BorderPane, ToolBar, ToggleButtons, workspace TabPane, ComboBox | Active destination and full scope visible, no hardcoded footer identity; saved navigation per workspace |
| First launch/discovery | staged GridPane form, ListView/TableView | No folder discovery. Product/channel/region/build/locale; discovery timestamp and capability. Empty offline has no sample results |
| Research home/search | category tiles, TextField, filters, virtualized ListView/TableView | Scope badge, counts/completeness, readable question and finding; explicit no-match vs not-indexed vs unavailable |
| Build/archive | paged TableView, scope Dialog, storage summary | Edition/build membership, metadata-only/partial/retained, current availability; logical, physical, shared, additional and reclaimable bytes separated |
| Record/file inspector | SplitPane, TableView, expandable TitledPane | Friendly and raw fields, ID, exact schema/layout, source hashes and community metadata attribution; relation history stays scoped |
| Comparison | paired selectors, TableView, field detail pane | Before/After column headings, file vs record interpretation, missing-scope warning, incompatible-schema state; no false Removed due to unavailable input |
| Notebook/watchlists | ListView, TextArea, query/scope dialog | Saved evidence and active watch status visible; edition/build provenance remains attached; unsaved changes and validation visible |
| Jobs | TableView/ListView, ProgressBar, stage labels | Queue/download/verify/decode/index/export; bytes shared vs transferred, cancel/retry and unavailable partial scope |
| Asset previews | ImageView, TextArea, MediaView where supported; staged preview pane | Metadata useful even unsupported/encrypted; no release/obtainability claim. Models/maps separate capability slice |
| Settings/cleanup | TabPane or grouped forms, review Dialog | One content store, derived usage separately; retention/reference-safe reclaimed bytes; confirmation details and protected references |
| Export preparation | Dialog and queued job detail | Format, selected result scope, destination, source/schema inclusion and missing dependencies; success links output |

## Mockup gaps to resolve before corresponding implementation

1. Shared research.js uses independent fixed wowt DEMO-104/105 while Atlas archive uses 650xx and footer fixed Retail. Replace with one coherent scope/data fixture and edition workspace switch mockup. Use one unambiguous fictional dataset per context.
2. Archive examples contain fixed inconsistent byte numbers (7.3 GB logical, 6.4 GB shared, 240 MB additional); design totals from a small object-reference fixture. Show unknown estimates as unknown, not zeros. Add requested vs verified coverage and timestamp.
3. Add scope selection (metadata, tables, selected assets, supported full retain) and partial/offline missing-object treatment. Archived must never mean complete unless coverage establishes it.
4. Add dual-build concurrent shared download illustration, derived reuse explanation, active-reader protection, and cleanup preview with exact released memberships, unique reclaimed bytes and retained shared bytes. Quota eviction differs from intentional retained-build deletion.
5. Add Files destination/file inspector, unknown listfile naming source, unsupported format, no index vs no matches, preloads advertised vs verified availability, unavailable exact schema, corrupt verification and comparison incomplete states. Current global state selector is a prototype review aid, not production UI.
6. Replace toast-only secondary actions and synthetic job Complete with real designed progress/validation transitions. Finish watchlist editing/scope/trigger/matching results and export preparation/job completion mockups. Complete relationship evidence links rather than text chains.
7. Keyboard table activation, modal focus return/trapping, status announcements, sortable columns and safe text wrapping need explicit specimens and review. A browser overview reference is available; actual JavaFX typography, contrast, interaction and per-platform screenshots must be verified during implementation.

## Small UI delivery slices / acceptance

A. Context and discovery: approved shell/context/discovery mockup including offline-empty, loading, error, unsupported capability; JavaFX shell displays fixture-driven edition/build identity at every destination. Keyboard selection and 200% scale pass.
B. Archive coverage/storage: mockups for old-build scope, shared object estimate, partial failure, retained offline and cleanup. Two overlapping build fixtures show accurate logical/physical/extra/reclaimable values and preserve shared/active objects on remove.
C. First research result: one scoped item query, actual table/record inspector and unavailable states. User can search, activate a row, follow a relation, return with filter/selection preserved, and copy full provenance without UI-thread stalls.
D. Comparison/notebook: coherent paired snapshots, field diff, missing scope/schema and save evidence mockups. Before/After source contexts visible; no unsupported cross-edition alignment; saved note reopens exact evidence.
E. Jobs/export: all actionable states and validation, cancellable async progress, export preparation and complete artifact. A failure has a visible reason and recovery; no stale response repaints another workspace.
F. Asset previews/settings: supported image/text first, then audio and separately scoped model/map capability. Unsupported/encrypted metadata remains inspectable; storage settings never introduce game-path reads.

For each slice: designer reviews screenshots against Atlas tokens; usability reviewer independently exercises task flow and accessibility; implementer owns behavioral tests and QA adds cross-platform UI checks. Do not call planning or static mockups validated production UI. No extra approval gate for already authorized routine work.

## Screenshot acceptance

Use [the browser overview reference](design/atlas-overview-reference.png) and the [interactive Atlas prototype](../mockups/atlas/index.html). This image is a design reference, not a JavaFX acceptance screenshot. Compare real application captures at matching sizes (initially 1440×1100 and 1280×800). Inspect layout, typography, spacing, color, visual hierarchy, focus, scaling and loading/empty/error/offline states. Use standard accessible controls and document intentional context/usability corrections. The reference must not force known mockup errors into production.

## Initial JavaFX visual evidence

The initial shell was captured on isolated Linux/Xvfb with a full JDK25 development distribution and synthetic fixtures:

- [Research at1440×1100](design/javafx-research-1440x1100.png)
- [Archive at1280×800](design/javafx-archive-1280x800.png)
- [Research at200%textscale/1280×800](design/javafx-research-200pct-1280x800.png)

Compared against Atlas: ivory/teal palette, serif headings, paper panels and research hierarchy are retained. The application deliberately adds persistent exact-build context and explicit fixture/capability disclosure. Scaling review found and fixed overlapping/truncated context fields by separating intrinsic product and coverage items. Real-FX harness assertions exercise source dialog/focus return, search retention, scale retention and asynchronous source-selection state. These checks are initial-shell evidence, not complete accessibility certification, all-platform visual acceptance or mining workflow validation.

### Saved metadata workspace increment (#15)

The actual JavaFX [saved-workspace catalog at 1280×800](design/javafx-saved-workspaces-1280x800.png) and [offline reopened locale at 200%](design/javafx-saved-workspace-200pct-1280x800.png) were reviewed against Atlas. A versioned two-process `CaptureWorkspaces` harness exercises actual SQLite save/restart with synthetic sources and rejects any discovery call. It also verifies saved/raw-source context isolation, locale-specific inspection, invalid/valid rename, cancelled/confirmed removal and preservation of the other saved workspace.

The tested slice passed visual/usability review. It preserves exact context and clearly identifies metadata-only coverage. At 200%, long picker labels can ellipsize and fixed context chrome leaves a smaller scrollable research area; the full selected source and locale remain visible. Compact research cards and a shorter save action corrected layout issues found in matching-size captures. This is not full-platform accessibility or complete research-workflow certification.
