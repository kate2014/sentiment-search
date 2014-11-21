package zx.soft.sent.solr.special;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zx.soft.sent.dao.common.MybatisConfig;
import zx.soft.sent.dao.domain.special.SpecialTopic;
import zx.soft.sent.dao.special.SpecialQuery;
import zx.soft.sent.solr.domain.FacetDateParams;
import zx.soft.sent.solr.domain.FacetDateResult;
import zx.soft.sent.solr.domain.QueryParams;
import zx.soft.sent.solr.domain.QueryResult;
import zx.soft.sent.solr.domain.SimpleFacetInfo;
import zx.soft.sent.solr.search.FacetSearch;
import zx.soft.sent.solr.search.SearchingData;
import zx.soft.utils.json.JsonUtils;
import zx.soft.utils.time.TimeUtils;

public class SpecialTopicRun {

	private static Logger logger = LoggerFactory.getLogger(SpecialTopicTimer.class);

	/**
	 * 主函数
	 */
	public static void main(String[] args) {
		SpecialTopicRun specialTopicRun = new SpecialTopicRun();
		specialTopicRun.run();
	}

	public void run() {
		try {
			logger.info("Running updating Tasks at:" + new Date().toString());
			SpecialQuery specialQuery = new SpecialQuery(MybatisConfig.ServerEnum.sentiment);
			SearchingData search = new SearchingData();
			// 在OA专题查询缓存数据表oa_special_query_cache中查询所有活跃的专题identify
			// 在这里认为，如果一个月内没有查询就不更新
			long start = System.currentTimeMillis() / 1000 - 30 * 86400;
			List<String> identifys = specialQuery.selectSpecialIdentifyByTime(start);
			// 循环更新每个专题的查询结果
			QueryParams queryParams = null;
			SpecialTopic specialInfo = null;
			FacetDateParams fdp = null;
			QueryResult pieResult = null;
			FacetDateResult trandResult = null;
			for (String identify : identifys) {
				logger.info("Updating identify=" + identify + " at:" + new Date().toString());
				// 查询专题信息
				specialInfo = specialQuery.selectSpecialInfo(identify);
				if (specialInfo != null) {
					// 从solr集群中查询饼状图结果
					queryParams = new QueryParams();
					queryParams.setQ(specialInfo.getKeywords());
					queryParams.setFq(getTimestampFilterQuery(specialInfo.getStart(), specialInfo.getEnd())
							+ ";country_code:" + specialInfo.getHometype());
					queryParams.setFacetField("platform");
					pieResult = search.queryData(queryParams, false);
					// 更新饼状图结果到数据库中
					if (specialQuery.selectSpecialResult(identify, "pie") == null) {
						specialQuery.insertSpecialResult(identify, "pie",
								JsonUtils.toJsonWithoutPretty(getPieChart(specialInfo, pieResult)));
					} else {
						specialQuery.updateSpecialResult(identify, "pie",
								JsonUtils.toJsonWithoutPretty(getPieChart(specialInfo, pieResult)));
					}
					//					System.out.println(JsonUtils.toJson(getPieChart(specialInfo, pieResult)));
					// 从solr集群中查询趋势图结果
					fdp = new FacetDateParams();
					fdp.setQ(transUnicode(specialInfo.getKeywords())); // URL中的部分字符需要编码转换
					fdp.setFacetDate("timestamp");
					fdp.setFacetDateStart(TimeUtils.transTimeStr(specialInfo.getStart()));
					fdp.setFacetDateEnd(TimeUtils.transTimeStr(specialInfo.getEnd()));
					fdp.setFacetDateGap("%2B1DAY");
					trandResult = FacetSearch.getFacetDates("timestamp", FacetSearch.getFacetDateResult(fdp));
					// 更新趋势图结果到数据库中
					if (specialQuery.selectSpecialResult(identify, "trend") == null) {
						specialQuery.insertSpecialResult(identify, "trend",
								JsonUtils.toJsonWithoutPretty(getTrendChart(specialInfo, trandResult)));
					} else {
						specialQuery.updateSpecialResult(identify, "trend",
								JsonUtils.toJsonWithoutPretty(getTrendChart(specialInfo, trandResult)));
					}
					//					System.out.println(JsonUtils.toJson(getTrendChart(specialInfo, trandResult)));
				}
			}
			search.close();
		} catch (Exception e) {
			logger.info("Exception=" + e.getMessage());
		}
	}

	private PieChart getPieChart(SpecialTopic specialInfo, QueryResult result) {
		PieChart pieChart = new PieChart();
		pieChart.setSpecialInfo(new SpecialInfo(specialInfo.getIdentify(), specialInfo.getName()));
		List<SimpleFacetInfo> facetFields = result.getFacetFields();
		for (SimpleFacetInfo facetField : facetFields) {
			if ("platform".equalsIgnoreCase(facetField.getName())) {
				pieChart.setPlatformCount(facetField.getValues());
			}
		}
		return pieChart;
	}

	private TrendChart getTrendChart(SpecialTopic specialInfo, FacetDateResult result) {
		TrendChart trendChart = new TrendChart();
		trendChart.setSpecialInfo(new SpecialInfo(specialInfo.getIdentify(), specialInfo.getName()));
		trendChart.setCountByDay(result.getDateCounts());
		return trendChart;
	}

	public static String getTimestampFilterQuery(String start, String end) {
		return "timestamp:[" + TimeUtils.transTimeStr(start) + " TO " + TimeUtils.transTimeStr(end) + "]";
	}

	public static String transUnicode(String str) {
		try {
			return URLEncoder.encode(str, "utf-8");
		} catch (UnsupportedEncodingException e) {
			logger.error("UnsupportedEncodingException e=" + e.getMessage());
			return "";
		}
	}

}
