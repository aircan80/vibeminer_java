package io.vibeminer.desktop;

import io.vibeminer.domain.selectedfile.EncodedCandidate;
import java.util.Locale;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

/** Isolated backend-driven candidate metadata view; deliberately has no acquisition controls. */
public final class SelectedFileCandidatesPane extends VBox {
  private final SelectedFilePresentationModel model;
  private final double scale;
  public SelectedFileCandidatesPane(SelectedFilePresentationModel model,double scale) {
    super(10);
    if(!Double.isFinite(scale)||scale<=0)throw new IllegalArgumentException("Positive text scale required");
    this.model=java.util.Objects.requireNonNull(model);this.scale=scale;
    getStyleClass().add("panel");refreshState();
  }
  public void refreshState() {
    var s=model.state();getChildren().clear();
    getChildren().addAll(copy("FILE REPRESENTATIONS","eyebrow"),copy("Inspect encoded representations","section-heading"),copy("Metadata only. No selected file contents are downloaded, decoded or previewed here.","body-copy"));
    if(s.variant()==null){getChildren().add(copy(s.message(),"body-copy"));return;}
    var v=s.variant();
    getChildren().add(copy(v.build().productId()+" / "+v.build().channel()+" / "+v.build().region()+" / "+v.build().versionLabel()+" · ID "+v.fileDataId()+" · locale view "+s.locale(),"body-copy"));
    getChildren().add(copy("Selected variant: locale flags "+hex(v.localeFlags())+" · content flags "+hex(v.contentFlags()),"secondary"));
    Label status=copy(s.message(),s.stage()==SelectedFilePresentationModel.Stage.FAILED?"notice-heading":"body-copy");status.setId("selected-file-status");status.setAccessibleText(s.message());getChildren().add(status);
    if(s.busy()) {
      ProgressIndicator p=new ProgressIndicator();p.setMaxSize(25,25);p.setAccessibleText("Checking representation metadata. Percentage unknown.");
      getChildren().add(new FlowPane(10,8,p,button("Cancel request",model::cancel,"selected-file-cancel")));
    } else {
      var limit=model.budgets().lookup();
      getChildren().add(copy("Check metadata: at most "+bytes(limit.maximumMetadataWireBytes())+" wire traffic and "+limit.maximumMetadataRequests()+" requests. No payload acquisition is authorized.","secondary"));
      getChildren().add(button(s.plan()==null?"Check representations":"Check representations again",model::check,"selected-file-check"));
    }
    if(s.plan()!=null) {
      TableView<EncodedCandidate> table=new TableView<>(FXCollections.observableArrayList(s.plan().candidates()));table.setId("selected-file-candidates");table.setAccessibleText("All returned encoded representations. Select or press Enter to inspect. Selection does not download content.");
      table.getColumns().add(column("Encoding identifier",EncodedCandidate::encodingKey,520));
      table.getColumns().add(column("Encoded size",c->c.encodedBytes().isPresent()?bytes(c.encodedBytes().getAsLong()):"Unknown",190));
      table.getColumns().add(column("Specification reference",c->c.specificationIndex().isPresent()?Long.toString(c.specificationIndex().getAsLong()):"Not supplied",190));
      table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
      double row=Math.max(43,16*scale+16);table.setFixedCellSize(row);table.setPrefHeight(Math.max(42,13*scale+22)+Math.min(4,s.plan().candidates().size())*row+2);table.setMinHeight(Region.USE_PREF_SIZE);table.setMaxHeight(Region.USE_PREF_SIZE);
      VBox detail=new VBox(8);detail.setId("selected-file-candidate-detail");
      if(s.candidate()!=null)table.getSelectionModel().select(s.candidate());
      showDetail(detail,s.candidate());
      table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{model.selectCandidate(b);showDetail(detail,b);});
      table.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ENTER){Node value=detail.lookup("#selected-file-candidate-value");if(value!=null)value.requestFocus();e.consume();}});
      detail.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ESCAPE){table.requestFocus();e.consume();}});
      getChildren().addAll(copy(s.plan().candidates().size()+" representations · none selected automatically","secondary"),table,detail);
      VBox proofs=new VBox(8,
          value("Exact build: "+v.build().productId()+" / "+v.build().channel()+" / "+v.build().region()+" / "+v.build().versionLabel()+"\nBuild configuration: "+v.build().buildConfig()+"\nCDN configuration: "+v.build().cdnConfig()+"\nFileDataID: "+v.fileDataId()+"\nDeclared content identity: "+v.contentKey()+"\nLocale/content flags: "+hex(v.localeFlags())+" / "+hex(v.contentFlags())+"\nRaw flags: "+hex(v.rawFlagsFirst())+" / "+hex(v.rawFlagsSecond())+" / "+Integer.toUnsignedString(v.rawFlagsThird())+"\nName hash: "+v.nameHash().orElse("Not present")+" (not a filename)","Full exact build and selected raw variant tuple"),
          evidence("Actual configuration verification",s.plan().configuration()),
          evidence("Actual retained root verification",s.plan().root()),
          value("Root source: "+v.rootSource()+"\nEncoding metadata source: "+s.plan().proof().encodingBootstrapSource()+"\nAssociation verifier: "+s.plan().proof().verifierRevision()+"\nAssociation proof: "+s.plan().proof().proofDigest(),"Current root and encoding association evidence"),
          copy("Acquisition evidence can refer to an earlier verified source reused by this build. This metadata proof does not verify a selected file payload.","secondary"));
      TitledPane source=new TitledPane("Source and association evidence",proofs);source.setExpanded(false);getChildren().add(source);
    }
  }
  private static void showDetail(VBox detail,EncodedCandidate c) {
    detail.getChildren().clear();
    if(c==null){detail.getChildren().add(copy("Choose a representation to inspect its full identity. No location lookup or payload download follows selection.","body-copy"));return;}
    TextArea raw=value("Encoding identifier: "+c.encodingKey()+"\nEncoded size: "+(c.encodedBytes().isPresent()?bytes(c.encodedBytes().getAsLong()):"Unknown")+"\nSpecification reference: "+(c.specificationIndex().isPresent()?Long.toString(c.specificationIndex().getAsLong()):"Not supplied"),"Full selected encoding identity and size");raw.setId("selected-file-candidate-value");detail.getChildren().add(raw);
    detail.getChildren().add(copy(c.encodedBytes().isEmpty()?"Unknown encoded size cannot authorize acquisition. Decoded content remains unverified.":"Known encoded size is not measured additional storage. No decoded size or decoded-content verification is established.","secondary"));
  }
  private static VBox evidence(String title,io.vibeminer.domain.VerifiedSourceAlias alias){return new VBox(4,copy(title,"eyebrow"),value("Actual source: "+alias.evidence().sourceUrl()+"\nVerified at: "+alias.evidence().verifiedAt()+"\nVerifier: "+alias.evidence().verifierSemantics()+"\nPhysical SHA-256: "+alias.object().digest().value()+"\nRetained bytes: "+bytes(alias.object().length())+"\nSource identity: "+alias.source(),title));}
  private static TableColumn<EncodedCandidate,String> column(String text,java.util.function.Function<EncodedCandidate,String> fn,double width){var c=new TableColumn<EncodedCandidate,String>(text);c.setCellValueFactory(v->new ReadOnlyStringWrapper(fn.apply(v.getValue())));c.setPrefWidth(width);return c;}
  private static TextArea value(String text,String accessible){var t=new TextArea(text);t.setEditable(false);t.setWrapText(true);t.setPrefRowCount(3);t.setAccessibleText(accessible);t.getStyleClass().add("identity-value");return t;}
  private static Label copy(String text,String style){var l=new Label(text);l.setWrapText(true);l.setMaxWidth(Double.MAX_VALUE);l.getStyleClass().add(style);return l;}
  private static Button button(String text,Runnable action,String id){var b=new Button(text);b.setId(id);b.setOnAction(e->action.run());b.getStyleClass().add("quiet-button");return b;}
  private static String bytes(long n){return String.format(Locale.ROOT,"%,d bytes",n);}
  private static String hex(long n){return "0x"+Long.toUnsignedString(n,16).toUpperCase(Locale.ROOT);}
}
