package org.batfish.question.routes;

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static java.util.Comparator.naturalOrder;
import static org.batfish.question.routes.RoutesAnswerer.COL_ADMIN_DISTANCES;
import static org.batfish.question.routes.RoutesAnswerer.COL_AS_PATHS;
import static org.batfish.question.routes.RoutesAnswerer.COL_COMMUNITIES;
import static org.batfish.question.routes.RoutesAnswerer.COL_LOCAL_PREFS;
import static org.batfish.question.routes.RoutesAnswerer.COL_METRICS;
import static org.batfish.question.routes.RoutesAnswerer.COL_NETWORK;
import static org.batfish.question.routes.RoutesAnswerer.COL_NEXT_HOPS;
import static org.batfish.question.routes.RoutesAnswerer.COL_NEXT_HOP_IPS;
import static org.batfish.question.routes.RoutesAnswerer.COL_NODE;
import static org.batfish.question.routes.RoutesAnswerer.COL_ORIGIN_PROTOCOLS;
import static org.batfish.question.routes.RoutesAnswerer.COL_PROTOCOLS;
import static org.batfish.question.routes.RoutesAnswerer.COL_TAGs;
import static org.batfish.question.routes.RoutesAnswerer.COL_VRF_NAME;
import static org.batfish.question.routes.RoutesAnswerer.getTableMetadata;
import static org.batfish.question.routes.RoutesAnswererUtil.computeNextHopNode;
import static org.batfish.question.routes.RoutesAnswererUtil.getBgpRouteRows;
import static org.batfish.question.routes.RoutesAnswererUtil.getMainRibRoutes;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import org.batfish.common.plugin.IBatfishTestAdapter;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.GenericRib;
import org.batfish.datamodel.InterfaceAddress;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpSpace;
import org.batfish.datamodel.LocalRoute;
import org.batfish.datamodel.MockDataPlane;
import org.batfish.datamodel.NetworkConfigurations;
import org.batfish.datamodel.NetworkFactory;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.Vrf;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.answers.Schema;
import org.batfish.datamodel.table.ColumnMetadata;
import org.batfish.datamodel.table.Row;
import org.batfish.question.routes.RoutesQuestion.RibProtocol;
import org.batfish.specifier.MockSpecifierContext;
import org.batfish.specifier.SpecifierContext;
import org.junit.Test;

/** Tests of {@link RoutesAnswerer}. */
public class RoutesAnswererTest {

  @Test
  public void testGetMainRibRoutesWhenEmptyRib() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1", ImmutableSortedMap.of(Configuration.DEFAULT_VRF_NAME, new MockRib<>()));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(ribs, ImmutableSet.of("n1"), null, ".*", ".*", null);

    assertThat(actual.entrySet(), hasSize(0));
  }

  @Test
  public void testHasNetworkFiltering() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1",
            ImmutableSortedMap.of(
                Configuration.DEFAULT_VRF_NAME,
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setNetwork(Prefix.parse("1.1.1.0/24"))
                            .setNextHopInterface("Null")
                            .setAdministrativeCost(1)
                            .build(),
                        StaticRoute.builder()
                            .setNetwork(Prefix.parse("2.2.2.0/24"))
                            .setNextHopInterface("Null")
                            .setAdministrativeCost(1)
                            .build()))));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(
            ribs, ImmutableSet.of("n1"), new Prefix(new Ip("2.2.2.0"), 24), ".*", ".*", null);

    assertThat(actual.entrySet(), hasSize(1));
    assertThat(
        actual.entrySet().iterator().next().getKey().getPrefix(),
        equalTo(Prefix.parse("2.2.2.0/24")));
  }

  @Test
  public void testHasNodeFiltering() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1",
            ImmutableSortedMap.of(
                Configuration.DEFAULT_VRF_NAME,
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setAdministrativeCost(1)
                            .setNetwork(Prefix.parse("1.1.1.0/24"))
                            .setNextHopInterface("Null")
                            .build()))));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(ribs, ImmutableSet.of("differentNode"), null, ".*", ".*", null);

    assertThat(actual.entrySet(), hasSize(0));
  }

  @Test
  public void testHasProtocolFiltering() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1",
            ImmutableSortedMap.of(
                Configuration.DEFAULT_VRF_NAME,
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setNetwork(Prefix.parse("1.1.1.0/24"))
                            .setNextHopInterface("Null")
                            .setAdministrativeCost(1)
                            .build(),
                        new LocalRoute(new InterfaceAddress("2.2.2.0/24"), "Null")))));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(ribs, ImmutableSet.of("n1"), null, "stati.*", ".*", null);

    assertThat(actual.entrySet(), hasSize(1));
    assertThat(
        actual.entrySet().iterator().next().getKey().getPrefix(),
        equalTo(Prefix.parse("1.1.1.0/24")));
  }

  @Test
  public void testHasVrfFiltering() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1",
            ImmutableSortedMap.of(
                Configuration.DEFAULT_VRF_NAME,
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setAdministrativeCost(1)
                            .setNetwork(Prefix.parse("1.1.1.0/24"))
                            .setNextHopInterface("Null")
                            .build())),
                "notDefaultVrf",
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setNetwork(Prefix.parse("2.2.2.0/24"))
                            .setNextHopInterface("Null")
                            .setAdministrativeCost(1)
                            .build()))));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(ribs, ImmutableSet.of("n1"), null, ".*", "^not.*", null);

    assertThat(actual.entrySet(), hasSize(1));
    assertThat(
        actual.entrySet().iterator().next().getKey().getPrefix(),
        equalTo(Prefix.parse("2.2.2.0/24")));
  }

  @Test
  public void testHasAdminDistanceValue() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1",
            ImmutableSortedMap.of(
                Configuration.DEFAULT_VRF_NAME,
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setNetwork(Prefix.parse("1.1.1.0/24"))
                            .setNextHopInterface("Null")
                            .setAdministrativeCost(10)
                            .build()))));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(ribs, ImmutableSet.of("n1"), null, ".*", ".*", null);

    assertThat(actual.values().iterator().next().iterator().next().getAdminDistance(), equalTo(10));
  }

  @Test
  public void testHasMetric() {
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            "n1",
            ImmutableSortedMap.of(
                Configuration.DEFAULT_VRF_NAME,
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setNetwork(Prefix.parse("1.1.1.0/24"))
                            .setNextHopInterface("Null")
                            .setMetric(111)
                            .setAdministrativeCost(1)
                            .build()))));

    Map<RouteRowKey, SortedSet<RouteRowAttribute>> actual =
        getMainRibRoutes(ribs, ImmutableSet.of("n1"), null, ".*", ".*", null);

    assertThat(actual.values().iterator().next().iterator().next().getMetric(), equalTo(111L));
  }

  @Test
  public void testGetTableMetadataProtocolAll() {
    List<ColumnMetadata> columnMetadata = getTableMetadata(RibProtocol.MAIN).getColumnMetadata();

    assertThat(
        columnMetadata
            .stream()
            .map(ColumnMetadata::getName)
            .collect(ImmutableList.toImmutableList()),
        contains(
            COL_NODE,
            COL_VRF_NAME,
            COL_NETWORK,
            COL_NEXT_HOPS,
            COL_NEXT_HOP_IPS,
            COL_PROTOCOLS,
            COL_ADMIN_DISTANCES,
            COL_METRICS,
            COL_TAGs));

    assertThat(
        columnMetadata
            .stream()
            .map(ColumnMetadata::getSchema)
            .collect(ImmutableList.toImmutableList()),
        contains(
            Schema.NODE,
            Schema.STRING,
            Schema.PREFIX,
            Schema.list(Schema.STRING),
            Schema.list(Schema.IP),
            Schema.list(Schema.STRING),
            Schema.list(Schema.INTEGER),
            Schema.list(Schema.INTEGER),
            Schema.list(Schema.INTEGER)));
  }

  @Test
  public void testGetTableMetadataBGP() {
    ImmutableList.Builder<String> expectedBuilder = ImmutableList.builder();
    expectedBuilder.add(
        COL_NODE,
        COL_VRF_NAME,
        COL_NETWORK,
        COL_NEXT_HOPS,
        COL_NEXT_HOP_IPS,
        COL_PROTOCOLS,
        // BGP attributes
        COL_AS_PATHS,
        COL_METRICS,
        COL_LOCAL_PREFS,
        COL_COMMUNITIES,
        COL_ORIGIN_PROTOCOLS,
        COL_TAGs);
    List<String> expected = expectedBuilder.build();

    for (RibProtocol rib : Arrays.asList(RibProtocol.BGP, RibProtocol.BGPMP)) {
      List<ColumnMetadata> columnMetadata = getTableMetadata(rib).getColumnMetadata();
      assertThat(
          columnMetadata
              .stream()
              .map(ColumnMetadata::getName)
              .collect(ImmutableList.toImmutableList()),
          equalTo(expected));
    }
  }

  @Test
  public void testGetBgpRoutesCommunities() {
    Multiset<Row> rows =
        getBgpRouteRows(
            ImmutableMap.of(
                new RouteRowKey("node", "vrf", Prefix.parse("1.2.3.4/24")),
                ImmutableSortedSet.of(
                    RouteRowAttribute.builder()
                        .setNextHopIp(new Ip("1.1.1.1"))
                        .setNextHop("node1")
                        .setCommunities("1:1")
                        .build(),
                    RouteRowAttribute.builder()
                        .setNextHopIp(new Ip("1.1.1.2"))
                        .setNextHop("node2")
                        .setCommunities("2:2")
                        .build())));

    assertThat(rows, hasSize(1));
    assertThat(
        rows.iterator().next().get(COL_COMMUNITIES, Schema.list(Schema.STRING)),
        equalTo(ImmutableList.of("1:1", "2:2")));
  }

  @Test
  public void testBgpRoutesHaveTagColumn() {
    Multiset<Row> rows =
        getBgpRouteRows(
            ImmutableMap.of(
                new RouteRowKey("node", "vrf", Prefix.parse("1.2.3.4/24")),
                ImmutableSortedSet.of(
                    RouteRowAttribute.builder()
                        .setNextHopIp(new Ip("1.1.1.1"))
                        .setNextHop("node1")
                        .setCommunities("1:1")
                        .build())));

    String nullTag = null;
    assertThat(
        rows.iterator().next().get(COL_TAGs, Schema.list(Schema.STRING)),
        equalTo(Lists.newArrayList(nullTag)));
  }

  @Test
  public void testComputeNextHopNode() {
    assertThat(computeNextHopNode(null, ImmutableMap.of()), nullValue());
    assertThat(computeNextHopNode(new Ip("1.1.1.1"), null), nullValue());
    assertThat(computeNextHopNode(new Ip("1.1.1.1"), ImmutableMap.of()), nullValue());
    assertThat(
        computeNextHopNode(
            new Ip("1.1.1.1"), ImmutableMap.of(new Ip("1.1.1.1"), ImmutableSet.of("n1"))),
        equalTo("n1"));
    assertThat(
        computeNextHopNode(
            new Ip("1.1.1.1"), ImmutableMap.of(new Ip("1.1.1.2"), ImmutableSet.of("n1"))),
        nullValue());
  }

  /** Run through full pipeline (create question and answerer), */
  @Test
  public void testFullAnswerPipelineAndNumResults() {
    // Setup mock data structures
    NetworkFactory nf = new NetworkFactory();
    Configuration c =
        nf.configurationBuilder().setConfigurationFormat(ConfigurationFormat.CISCO_IOS).build();
    Vrf vrf = nf.vrfBuilder().setOwner(c).build();
    SortedMap<String, SortedMap<String, GenericRib<AbstractRoute>>> ribs =
        ImmutableSortedMap.of(
            c.getHostname(),
            ImmutableSortedMap.of(
                vrf.getName(),
                new MockRib<>(
                    ImmutableSet.of(
                        StaticRoute.builder()
                            .setAdministrativeCost(1)
                            .setNetwork(Prefix.parse("1.1.1.1/32"))
                            .setNextHopInterface("Null")
                            .build()))));
    NetworkConfigurations nc = NetworkConfigurations.of(ImmutableMap.of(c.getHostname(), c));

    AnswerElement el =
        new RoutesAnswerer(
                new RoutesQuestion(),
                new MockBatfish(nc, MockDataPlane.builder().setRibs(ribs).build()))
            .answer();

    assert el.getSummary() != null;
    assertThat(el.getSummary().getNumResults(), equalTo(1));

    // no results for empty ribs
    el =
        new RoutesAnswerer(
                new RoutesQuestion(),
                new MockBatfish(
                    nc, MockDataPlane.builder().setRibs(ImmutableSortedMap.of()).build()))
            .answer();
    assert el.getSummary() != null;
    assertThat(el.getSummary().getNumResults(), equalTo(0));
  }

  @Test
  public void testHasTextDesc() {
    String textDesc = getTableMetadata(RibProtocol.MAIN).getTextDesc();

    assertThat(textDesc, notNullValue());
    assertThat(textDesc, not(emptyString()));
  }

  static class MockBatfish extends IBatfishTestAdapter {

    private final NetworkConfigurations _configs;
    private final DataPlane _dp;

    public MockBatfish(NetworkConfigurations configs, DataPlane dp) {
      _configs = configs;
      _dp = dp;
    }

    @Override
    public SortedMap<String, Configuration> loadConfigurations() {
      return _configs
          .getMap()
          .entrySet()
          .stream()
          .collect(toImmutableSortedMap(naturalOrder(), Entry::getKey, Entry::getValue));
    }

    @Override
    public DataPlane loadDataPlane() {
      return _dp;
    }

    @Override
    public SpecifierContext specifierContext() {
      return MockSpecifierContext.builder().setConfigs(loadConfigurations()).build();
    }
  }

  /** Mock rib that only supports one operation: returning pre-set routes. */
  static class MockRib<R extends AbstractRoute> implements GenericRib<R> {

    private static final long serialVersionUID = 1L;

    private Set<R> _routes;

    MockRib() {
      _routes = ImmutableSet.of();
    }

    MockRib(Set<R> routes) {
      _routes = routes;
    }

    @Override
    public int comparePreference(R lhs, R rhs) {
      return 0;
    }

    @Override
    public Map<Prefix, IpSpace> getMatchingIps() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SortedSet<Prefix> getPrefixes() {
      throw new UnsupportedOperationException();
    }

    @Override
    public IpSpace getRoutableIps() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<R> getRoutes() {
      return _routes;
    }

    @Override
    public Set<R> longestPrefixMatch(Ip address) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<R> longestPrefixMatch(Ip address, int maxPrefixLength) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean mergeRoute(R route) {
      throw new UnsupportedOperationException();
    }
  }
}
