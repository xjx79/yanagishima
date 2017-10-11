package yanagishima.service;

import com.facebook.presto.client.*;
import com.google.common.collect.Lists;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import me.geso.tinyorm.TinyORM;
import okhttp3.OkHttpClient;
import org.codehaus.jackson.map.ObjectMapper;
import org.komamitsu.fluency.Fluency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yanagishima.config.YanagishimaConfig;
import yanagishima.exception.QueryErrorException;
import yanagishima.result.PrestoQueryResult;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.facebook.presto.client.OkHttpUtil.basicAuth;
import static com.facebook.presto.client.OkHttpUtil.setupTimeouts;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static yanagishima.util.DbUtil.insertQueryHistory;
import static yanagishima.util.DbUtil.storeError;
import static yanagishima.util.PathUtil.getResultFilePath;
import static yanagishima.util.TimeoutUtil.checkTimeout;

public class PrestoServiceImpl implements PrestoService {

    private static Logger LOGGER = LoggerFactory.getLogger(PrestoServiceImpl.class);

    private YanagishimaConfig yanagishimaConfig;

    private OkHttpClient httpClient;

    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Inject
    private TinyORM db;

    @Inject
    public PrestoServiceImpl(YanagishimaConfig yanagishimaConfig) {
        this.yanagishimaConfig = yanagishimaConfig;
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        setupTimeouts(builder, 5, SECONDS);
        httpClient = builder.build();
    }

    @Override
    public String doQueryAsync(String datasource, String query, String userName, Optional<String> prestoUser, Optional<String> prestoPassword) {
        StatementClient client = getStatementClient(datasource, query, userName, prestoUser, prestoPassword);
        executorService.submit(new Task(datasource, query, client, userName));
        return client.current().getId();
    }

    public class Task implements Runnable {
        private String datasource;
        private String query;
        private StatementClient client;
        private String userName;

        public Task(String datasource, String query, StatementClient client, String userName) {
            this.datasource = datasource;
            this.query = query;
            this.client = client;
            this.userName = userName;
        }

        @Override
        public void run() {
            try {
                int limit = yanagishimaConfig.getSelectLimit();
                getPrestoQueryResult(this.datasource, this.query, this.client, true, limit, this.userName);
            } catch (Throwable e) {
                LOGGER.error(e.getMessage(), e);
            } finally {
                if(this.client != null) {
                    this.client.close();
                }
            }
        }
    }

    @Override
    public PrestoQueryResult doQuery(String datasource, String query, String userName, Optional<String> prestoUser, Optional<String> prestoPassword, boolean storeFlag, int limit) throws QueryErrorException {
        try (StatementClient client = getStatementClient(datasource, query, userName, prestoUser, prestoPassword)) {
            return getPrestoQueryResult(datasource, query, client, storeFlag, limit, userName);
        }
    }

    private PrestoQueryResult getPrestoQueryResult(String datasource, String query, StatementClient client, boolean storeFlag, int limit, String userName) throws QueryErrorException {
        Duration queryMaxRunTime = new Duration(this.yanagishimaConfig.getQueryMaxRunTimeSeconds(datasource), TimeUnit.SECONDS);
        long start = System.currentTimeMillis();
        while (client.isValid() && (client.current().getData() == null)) {
            client.advance();
            if(System.currentTimeMillis() - start > queryMaxRunTime.toMillis()) {
                String queryId = client.current().getId();
                String message = format("Query failed (#%s) in %s: Query exceeded maximum time limit of %s", queryId, datasource, queryMaxRunTime.toString());
                storeError(db, datasource, "presto", queryId, query, userName, message);
                throw new RuntimeException(message);
            }
        }

        PrestoQueryResult prestoQueryResult = new PrestoQueryResult();
        if ((!client.isFailed()) && (!client.isGone()) && (!client.isClosed())) {
            QueryResults results = client.isValid() ? client.current() : client.finalResults();
            String queryId = results.getId();
            if (results.getColumns() == null) {
                throw new QueryErrorException(new SQLException(format("Query %s has no columns\n", results.getId())));
            } else {
                prestoQueryResult.setQueryId(queryId);
                prestoQueryResult.setUpdateType(results.getUpdateType());
                List<String> columns = Lists.transform(results.getColumns(), Column::getName);
                prestoQueryResult.setColumns(columns);
                List<List<String>> rowDataList = new ArrayList<List<String>>();
                processData(client, datasource, queryId, query, prestoQueryResult, columns, rowDataList, start, limit, userName);
                prestoQueryResult.setRecords(rowDataList);
                if(storeFlag) {
                    insertQueryHistory(db, datasource, "presto", query, userName, queryId);
                }
                if(yanagishimaConfig.getFluentdExecutedTag().isPresent()) {
                    String fluentdHost = yanagishimaConfig.getFluentdHost().orElse("localhost");
                    int fluentdPort = Integer.parseInt(yanagishimaConfig.getFluentdPort().orElse("24224"));
                    try (Fluency fluency = Fluency.defaultFluency(fluentdHost, fluentdPort)) {
                        long end = System.currentTimeMillis();
                        String tag = yanagishimaConfig.getFluentdExecutedTag().get();
                        Map<String, Object> event = new HashMap<>();
                        event.put("elapsed_time_millseconds", end - start);
                        event.put("user", userName);
                        event.put("query", query);
                        event.put("query_id", queryId);
                        event.put("datasource", datasource);
                        event.put("engine", "presto");
                        fluency.emit(tag, event);
                    } catch (IOException e) {
                        LOGGER.error(e.getMessage(), e);
                    }
                }
            }
        }

        if (client.isClosed()) {
            throw new RuntimeException("Query aborted by user");
        } else if (client.isGone()) {
            throw new RuntimeException("Query is gone (server restarted?)");
        } else if (client.isFailed()) {
            QueryResults results = client.finalResults();
            if(prestoQueryResult.getQueryId() == null) {
                String queryId = results.getId();
                String message = format("Query failed (#%s) in %s: %s", queryId, datasource, results.getError().getMessage());
                storeError(db, datasource, "presto", queryId, query, userName, message);
            } else {
                Path successDst = getResultFilePath(datasource, prestoQueryResult.getQueryId(), false);
                try {
                    Files.delete(successDst);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Path dst = getResultFilePath(datasource, prestoQueryResult.getQueryId(), true);
                String message = format("Query failed (#%s) in %s: %s", prestoQueryResult.getQueryId(), datasource, results.getError().getMessage());

                try (BufferedWriter bw = Files.newBufferedWriter(dst, StandardCharsets.UTF_8)) {
                    bw.write(message);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if(yanagishimaConfig.getFluentdFaliedTag().isPresent()) {
                String fluentdHost = yanagishimaConfig.getFluentdHost().orElse("localhost");
                int fluentdPort = Integer.parseInt(yanagishimaConfig.getFluentdPort().orElse("24224"));
                try (Fluency fluency = Fluency.defaultFluency(fluentdHost, fluentdPort)) {
                    long end = System.currentTimeMillis();
                    String tag = yanagishimaConfig.getFluentdFaliedTag().get();
                    String queryId = results.getId();
                    String errorName = results.getError().getErrorName();
                    String errorType = results.getError().getErrorType();
                    Map<String, Object> event = new HashMap<>();
                    event.put("elapsed_time_millseconds", end - start);
                    event.put("user", userName);
                    event.put("query", query);
                    event.put("query_id", queryId);
                    event.put("datasource", datasource);
                    event.put("errorName", errorName);
                    event.put("errorType", errorType);
                    fluency.emit(tag, event);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            throw resultsException(results);
        }
        return prestoQueryResult;
    }

    private void processData(StatementClient client, String datasource, String queryId, String query, PrestoQueryResult prestoQueryResult, List<String> columns, List<List<String>> rowDataList, long start, int limit, String userName) {
        Duration queryMaxRunTime = new Duration(this.yanagishimaConfig.getQueryMaxRunTimeSeconds(datasource), TimeUnit.SECONDS);
        Path dst = getResultFilePath(datasource, queryId, false);
        int lineNumber = 0;
        int maxResultFileByteSize = yanagishimaConfig.getMaxResultFileByteSize();
        int resultBytes = 0;
        try (BufferedWriter bw = Files.newBufferedWriter(dst, StandardCharsets.UTF_8)) {
            ObjectMapper columnsMapper = new ObjectMapper();
            String columnsStr = columnsMapper.writeValueAsString(columns) + "\n";
            bw.write(columnsStr);
            lineNumber++;
            while (client.isValid()) {
                Iterable<List<Object>> data = client.current().getData();
                if (data != null) {
                    for(List<Object> row : data) {
                        List<String> columnDataList = new ArrayList<>();
                        List<Object> tmpColumnDataList = row.stream().collect(Collectors.toList());
                        for (Object tmpColumnData : tmpColumnDataList) {
                            if (tmpColumnData instanceof Long) {
                                columnDataList.add(((Long) tmpColumnData).toString());
                            } else if (tmpColumnData instanceof Double) {
                                if(Double.isNaN((Double)tmpColumnData) || Double.isInfinite((Double) tmpColumnData)) {
                                    columnDataList.add(tmpColumnData.toString());
                                } else {
                                    columnDataList.add(BigDecimal.valueOf((Double) tmpColumnData).toPlainString());
                                }
                            } else {
                                if (tmpColumnData == null) {
                                    columnDataList.add(null);
                                } else {
                                    columnDataList.add(tmpColumnData.toString());
                                }
                            }
                        }
                        try {
                            ObjectMapper resultMapper = new ObjectMapper();
                            String resultStr = resultMapper.writeValueAsString(columnDataList) + "\n";
                            bw.write(resultStr);
                            lineNumber++;
                            resultBytes += resultStr.getBytes(StandardCharsets.UTF_8).length;
                            if(resultBytes > maxResultFileByteSize) {
                                String message = String.format("Result file size exceeded %s bytes. queryId=%s, datasource=%s", maxResultFileByteSize, queryId, datasource);
                                storeError(db, datasource, "presto", client.current().getId(), query, userName, message);
                                throw new RuntimeException(message);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (client.getQuery().toLowerCase().startsWith("show") || rowDataList.size() < limit) {
                            rowDataList.add(columnDataList);
                        } else {
                            prestoQueryResult.setWarningMessage(String.format("now fetch size is %d. This is more than %d. So, fetch operation stopped.", rowDataList.size(), limit));
                        }
                    }
                }
                client.advance();
                checkTimeout(db, queryMaxRunTime, start, datasource, "presto", queryId, query, userName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        prestoQueryResult.setLineNumber(lineNumber);
        try {
            long size = Files.size(dst);
            DataSize rawDataSize = new DataSize(size, DataSize.Unit.BYTE);
            prestoQueryResult.setRawDataSize(rawDataSize.convertToMostSuccinctDataSize());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private StatementClient getStatementClient(String datasource, String query, String userName, Optional<String> prestoUser, Optional<String> prestoPassword) {
        String prestoCoordinatorServer = yanagishimaConfig
                .getPrestoCoordinatorServer(datasource);
        String catalog = yanagishimaConfig.getCatalog(datasource);
        String schema = yanagishimaConfig.getSchema(datasource);

        String source = yanagishimaConfig.getSource();

        if (prestoUser.isPresent() && prestoPassword.isPresent()) {
            ClientSession clientSession = new ClientSession(
                    URI.create(prestoCoordinatorServer), prestoUser.get(), source, null, catalog,
                    schema, TimeZone.getDefault().getID(), Locale.getDefault(),
                    new HashMap<String, String>(), null, false, new Duration(2, MINUTES));
            checkArgument(clientSession.getServer().getScheme().equalsIgnoreCase("https"),
                    "Authentication using username/password requires HTTPS to be enabled");
            OkHttpClient.Builder clientBuilder = httpClient.newBuilder();
            clientBuilder.addInterceptor(basicAuth(prestoUser.get(), prestoPassword.get()));
            return new StatementClient(clientBuilder.build(), clientSession, query);
        }

        String user = null;
        if(userName == null ) {
            user = yanagishimaConfig.getUser();
        } else {
            user = userName;
        }

        ClientSession clientSession = new ClientSession(
                URI.create(prestoCoordinatorServer), user, source, null, catalog,
                schema, TimeZone.getDefault().getID(), Locale.getDefault(),
                new HashMap<String, String>(), null, false, new Duration(2, MINUTES));

        return new StatementClient(httpClient, clientSession, query);
    }

    private QueryErrorException resultsException(QueryResults results) {
        QueryError error = results.getError();
        String message = format("Query failed (#%s): %s", results.getId(), error.getMessage());
        Throwable cause = (error.getFailureInfo() == null) ? null : error.getFailureInfo().toException();
        return new QueryErrorException(results.getId(), error, new SQLException(message, error.getSqlState(), error.getErrorCode(), cause));
    }

}
