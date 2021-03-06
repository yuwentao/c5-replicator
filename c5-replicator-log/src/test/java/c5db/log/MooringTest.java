/*
 * Copyright 2014 WANdisco
 *
 *  WANdisco licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package c5db.log;

import c5db.interfaces.replication.QuorumConfiguration;
import c5db.interfaces.replication.ReplicatorLog;
import c5db.replication.generated.LogEntry;
import com.google.common.collect.Lists;
import org.jmock.Expectations;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static c5db.FutureActions.returnFutureWithValue;
import static c5db.log.OLogEntryOracle.QuorumConfigurationWithSeqNum;
import static c5db.replication.ReplicatorTestUtil.makeConfigurationEntry;
import static c5db.replication.ReplicatorTestUtil.makeProtostuffEntry;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;


public class MooringTest {
  @Rule
  public JUnitRuleMockery context = new JUnitRuleMockery();
  private final OLog oLog = context.mock(OLog.class);
  private final String quorumId = "quorumId";
  private ReplicatorLog log;

  @Before
  public void accessesOLogToObtainTheLastTermAndIndexWhenItIsConstructed() throws Exception {
    context.checking(new Expectations() {{
      oneOf(oLog).openAsync(quorumId);
      will(returnFutureWithValue(null));

      allowing(oLog).getLastTerm(quorumId);
      will(returnValue(0L));

      allowing(oLog).getNextSeqNum(quorumId);
      will(returnValue(1L));

      oneOf(oLog).getLastQuorumConfig(quorumId);
      will(returnValue(zeroConfiguration()));
    }});

    log = new Mooring(oLog, quorumId);
  }

  @Test
  public void returnsZeroFromGetLastTermWhenLogIsEmpty() {
    ignoringLog();
    assertThat(log.getLastTerm(), is(equalTo(0L)));
  }

  @Test
  public void returnsZeroFromGetLastIndexWhenLogIsEmpty() {
    ignoringLog();
    assertThat(log.getLastIndex(), is(equalTo(0L)));
  }

  @Test(expected = Exception.class)
  public void doesNotAcceptARequestToLogAnEmptyEntryList() {
    log.logEntries(new ArrayList<>());
  }

  @Test
  public void canReturnTheTermAndIndexOfTheLastEntryLogged() {
    expectLoggingNTimes(1);

    log.logEntries(
        singleEntryList(index(12), term(34), someData()));

    assertThat(log.getLastIndex(), is(equalTo(12L)));
    assertThat(log.getLastTerm(), is(equalTo(34L)));
  }

  @Test
  public void delegatesLogAndTruncationRequestsToOLog() {
    long index = 12;

    expectLoggingNTimes(1);
    expectTruncationNTimes(1);
    oLogGetTermWillReturn(0);

    context.checking(new Expectations() {{
      oneOf(oLog).getLastQuorumConfig(quorumId);
      will(returnValue(zeroConfiguration()));
    }});

    log.logEntries(
        singleEntryList(index, term(34), someData()));

    log.truncateLog(index);
  }

  @Test
  public void canReturnTheTermAndIndexOfAnEntryAfterPerformingATruncation() {
    long termOfFirstEntry = 34;
    long indexOfFirstEntry = 12;

    expectLoggingNTimes(1);
    expectTruncationNTimes(1);
    oLogGetTermWillReturn(termOfFirstEntry);

    context.checking(new Expectations() {{
      oneOf(oLog).getLastQuorumConfig(quorumId);
      will(returnValue(zeroConfiguration()));
    }});

    log.logEntries(
        Lists.newArrayList(
            makeProtostuffEntry(indexOfFirstEntry, termOfFirstEntry, someData()),
            makeProtostuffEntry(indexOfFirstEntry + 1, term(35), someData())));
    log.truncateLog(indexOfFirstEntry + 1);

    assertThat(log.getLastIndex(), is(equalTo(indexOfFirstEntry)));
    assertThat(log.getLastTerm(), is(equalTo(termOfFirstEntry)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void throwsAnExceptionIfAskedToTruncateToAnIndexOfZero() {
    log.truncateLog(0);
  }

  @Test
  public void storesAndRetrievesTheLastQuorumConfigurationLogged() {
    final long term = 7;
    final QuorumConfiguration config = aQuorumConfiguration();

    expectLoggingNTimes(1);

    log.logEntries(
        Lists.newArrayList(
            makeProtostuffEntry(index(2), term, someData()),
            makeConfigurationEntry(index(3), term, config),
            makeProtostuffEntry(index(4), term, someData())
        ));

    assertThat(log.getLastConfiguration(), is(equalTo(config)));
    assertThat(log.getLastConfigurationIndex(), is(equalTo(3L)));
  }

  @Test
  public void retrievesTheEmptyQuorumConfigurationWhenTheLogIsEmpty() {
    assertThat(log.getLastConfiguration(), is(equalTo(QuorumConfiguration.EMPTY)));
    assertThat(log.getLastConfigurationIndex(), is(equalTo(0L)));
  }

  @Test
  public void retrievesAnEarlierQuorumConfigurationWhenALaterOneIsTruncated() {
    final long term = 7;
    final QuorumConfiguration firstConfig = aQuorumConfiguration();
    final long firstConfigSeqNum = 777;
    final QuorumConfiguration secondConfig = firstConfig.getCompletedConfiguration();
    final long secondConfigSeqNum = firstConfigSeqNum + 1;

    expectLoggingNTimes(1);
    expectTruncationNTimes(1);
    allowOLogGetTerm();

    log.logEntries(
        Lists.newArrayList(
            makeConfigurationEntry(firstConfigSeqNum, term, firstConfig),
            makeConfigurationEntry(secondConfigSeqNum, term, secondConfig)
        ));

    assertThat(log.getLastConfiguration(), is(equalTo(secondConfig)));
    assertThat(log.getLastConfigurationIndex(), is(equalTo(secondConfigSeqNum)));

    context.checking(new Expectations() {{
      oneOf(oLog).getLastQuorumConfig(quorumId);
      will(returnValue(new QuorumConfigurationWithSeqNum(firstConfig, firstConfigSeqNum)));
    }});

    log.truncateLog(secondConfigSeqNum);

    assertThat(log.getLastConfiguration(), is(equalTo(firstConfig)));
    assertThat(log.getLastConfigurationIndex(), is(equalTo(firstConfigSeqNum)));
  }


  private long index(long i) {
    return i;
  }

  private long term(long i) {
    return i;
  }

  private String someData() {
    return "test data";
  }

  private QuorumConfiguration aQuorumConfiguration() {
    return QuorumConfiguration
        .of(Lists.newArrayList(1L, 2L, 3L))
        .getTransitionalConfiguration(Lists.newArrayList(4L, 5L, 6L));
  }

  private List<LogEntry> singleEntryList(long index, long term, String stringData) {
    return Lists.newArrayList(makeProtostuffEntry(index, term, stringData));
  }

  @SuppressWarnings("unchecked")
  private void expectLoggingNTimes(int n) {
    context.checking(new Expectations() {{
      exactly(n).of(oLog).logEntries(with.is(any(List.class)), with(any(String.class)));
    }});
  }

  private void expectTruncationNTimes(int n) {
    context.checking(new Expectations() {{
      exactly(n).of(oLog).truncateLog(with(any(Long.class)), with(any(String.class)));
    }});
  }

  private void oLogGetTermWillReturn(long expectedTerm) {
    context.checking(new Expectations() {{
      exactly(1).of(oLog).getLogTerm(with(any(Long.class)), with(any(String.class)));
      will(returnValue(expectedTerm));
    }});
  }

  private void allowOLogGetTerm() {
    context.checking(new Expectations() {{
      allowing(oLog).getLogTerm(with(any(Long.class)), with(any(String.class)));
    }});
  }

  private void ignoringLog() {
    context.checking(new Expectations() {{
      ignoring(oLog);
    }});
  }

  private QuorumConfigurationWithSeqNum zeroConfiguration() {
    return new QuorumConfigurationWithSeqNum(QuorumConfiguration.EMPTY, 0);
  }
}
