/* Author		: Beltus Ambe
 * Date			: Dec. 17th 2018
 * Description	: StockDetails table has referential integrity to Stock table. Therefore to update
 * 				  StockDetails, retrieve entire list from Stock table and loop through in order
 * 				  to update corresponding entry in StockDetails table. Add if entry is not present;
 * 				  update if entry is present.
 */

package com.stock.stockdetails;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.stock.movingaverage.MovingAverageRepositoryInterface;
import com.stock.historicalprices.HistPriceDAO;
import com.stock.historicalprices.HistPriceData;
import com.stock.historicalprices.HistPriceJSON;
import com.stock.historicalprices.HistPriceRepositoryInterface;
import com.stock.miscfunctions.*;
import com.stock.movingaverage.*;

@Service
public class StockDetailsService
{
	@Autowired
	private StockRepositoryInterface stockRepository;
	@Autowired
	private StockDetailsRepositoryInterface stockDetailsRepository;
	@Autowired
	private MovingAverageRepositoryInterface movingAverageRepository;
	@Autowired
	private HistPriceRepositoryInterface histPriceRepository;
	
	private final int maxDateSize = 10;
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private final RestTemplate restCaller;
	private String symbol;
	private String function;
	private String interval;
	private int period;
	private String apiKey;
	
	/* Constructor initializes the REST template builder to call the IEX web service */
	
	public StockDetailsService(RestTemplateBuilder restTemplateBuilder)
	{
		this.restCaller = restTemplateBuilder.build();
	}
	
	/* 
	 * This method returns details for the StockDetails table. Since there are two GET requests,
	 * that are required to form a message, the final StockDetails message is gotten from
	 * merging the two GET requests.
	 */
	
	public StockDetails getIEXStockDetails(String symbol)
	{
		logger.info(">>>>>> StockDetailsService::getIEXStockDetails ...");
		StockDetails mergedDetails = null;
		
		String keyStatURI = "https://api.iextrading.com/1.0/stock/" + symbol + "/stats";
		String quoteURI = "https://api.iextrading.com/1.0/stock/" + symbol + "/quote";

		logger.info(">>>>>> StockDetailsService::getIEXStockDetails - Calling IEX for key stats details");
		StockDetails keyStats = restCaller.getForObject(keyStatURI, StockDetails.class);
		
		logger.info(">>>>>> StockDetailsService::getIEXStockDetails - Calling IEX for key quote details");
		StockDetails quoteDetails = restCaller.getForObject(quoteURI, StockDetails.class);
		
		keyStats.setVolume(quoteDetails.getVolume());
		keyStats.setPrice(quoteDetails.getPrice());
		mergedDetails = keyStats;
		
		logger.info("<<<<<< StockDetailsService::getIEXStockDetails");
		return mergedDetails;
	}
	
	/*
	 * This method returns the moving average data from AlphaVantage. It's not possible to use a list for
	 * object mapping with AlphaVantage JSON data. This requires using a Map in order to
	 * get the data. Found it much easier to use a separate class to map the data and
	 * then use a loop to populate a JPA DAO object for populating it in the database.
	*/

	public MovingAverageDAO[] getMovingAverageData()
	{
		/*Only one month worth if moving average data is necessary for performance and trading purposes*/
		
		MovingAverageDAO [] movingAverageDAO = new MovingAverageDAO[(maxDateSize)];
		
		String mvAvgURI = "https://www.alphavantage.co/query?function=" + this.function + "&symbol=" + this.symbol +
							"&interval=" + interval + "&time_period=" + this.period + "&series_type=open&apikey=" + this.apiKey;
		
		//System.out.println("RESTCALLER REQUEST IS " + mvAvgURI);
		logger.info(">>>>>> StockDetailsService::getMovingAverageData - Calling Alpha Vantage for moving average details");
		MovingAverageJSON movingAvgData = restCaller.getForObject(mvAvgURI, MovingAverageJSON.class);
		
		/*
		 * Get the LinkedMap piece of the return data and store in a local variable.
		 * Then populate it into the MovingAverage JPA DAO.
		 */
		LinkedHashMap<String, MAData> maMap = movingAvgData.getMovingAverageDate();
		
		/*
		 * The format of MovingAverageDAO 'MetaData + each key/value pair of maMap
		 * LinkedHashMap. Only fetch on 30 days worth of moving average data
		 */

		try
		{
			int i = 0;
			for(Map.Entry<String, MAData> entry : maMap.entrySet())
			{
				movingAverageDAO[i] = new MovingAverageDAO();
				movingAverageDAO[i].setSymbol(movingAvgData.getMetadata().getSymbol());
				movingAverageDAO[i].setAverageType(movingAvgData.getMetadata().getAverageType());
				movingAverageDAO[i].setInterval(movingAvgData.getMetadata().getInterval());
				movingAverageDAO[i].setTimePeriod(movingAvgData.getMetadata().getTimePeriod());
				movingAverageDAO[i].setMovingAverageDate(MiscFunctions.getDateFromString(entry.getKey()));
				movingAverageDAO[i].setValue(entry.getValue().getMovingAverageValue());
				movingAverageDAO[i].setLastUpdateDate(MiscFunctions.getCurrentDateTime());
				
				logger.info("Symbol : " + movingAverageDAO[i].getSymbol());
				logger.info("MA Type : " + movingAverageDAO[i].getAverageType());
				logger.info("Interval : " + movingAverageDAO[i].getInterval());
				logger.info("Time Period : " + movingAverageDAO[i].getTimePeriod());
				logger.info("MA Date : " + movingAverageDAO[i].getMovingAverageDate());
				logger.info("MA Value : " + movingAverageDAO[i].getValue());
				logger.info("LastUpdateDate : " + movingAverageDAO[i].getLastUpdateDate().toString());
			
				i++;
				if (i >= maxDateSize)
					break;
			}
		}
		catch(Exception e)
		{
			logger.info("<<<<<< StockDetailsService::getMovingAverageData - An error occurred retrieving moving average data from JSON buffer");
			e.printStackTrace();
		}

		logger.info("<<<<<< StockDetailsService::getMovingAverageData");
		return movingAverageDAO;
	}
	
	/*
	 * Retrieve the historical price data from the return JSON buffer and create a date/closing price key/value
	 * pair to load in the database. This information is mapped from the HistPriceJSON class. Sample is displayed
	 * below.
	 * "Time Series (Daily)": {
        "2018-12-14": {
            "1. open": "108.2500",
            "2. high": "109.2600",
            "3. low": "105.5000",
            "4. close": "106.0300",
            "5. volume": "46959680"
        },
        "2018-12-13": {
            "1. open": "109.5800",
            "2. high": "110.8700",
            "3. low": "108.6300",
            "4. close": "109.4500",
            "5. volume": "31333362"
        },
        "2018-12-12": {
            "1. open": "110.8900",
            "2. high": "111.2700",
            "3. low": "109.0400",
            "4. close": "109.0800",
            "5. volume": "36183020"
        }
        }
	 */
	
	public HistPriceDAO[] getHistoricalPrices()
	{
		HistPriceDAO [] histPriceDAO = new HistPriceDAO[maxDateSize];
		String histPriceURI = "https://www.alphavantage.co/query?function=" + this.getFunction() + "&symbol=" + this.getSymbol() + "&apikey=" + this.apiKey;
		
		logger.info(">>>>>> StockDetailsService::getHistoricalPrices - Calling Alpha Vantage for historical prices details ...");
		HistPriceJSON histPriceJSON = restCaller.getForObject(histPriceURI, HistPriceJSON.class);
		LinkedHashMap<String, HistPriceData> histPriceDate = histPriceJSON.getHistPriceDate();
		
		try
		{
			int i = 0;
			for (Map.Entry<String, HistPriceData> entry : histPriceDate.entrySet())
			{
				histPriceDAO[i] = new HistPriceDAO();
				histPriceDAO[i].setSymbol(this.getSymbol());
				histPriceDAO[i].setClosingDate(MiscFunctions.getDateFromString(entry.getKey()));
				histPriceDAO[i].setClosingPrice(entry.getValue().getClosingPrice());
				histPriceDAO[i].setLastupdatedate(MiscFunctions.getCurrentDateTime());
				
				logger.info("Symbol : " + histPriceDAO[i].getSymbol());
				logger.info("Closing Date : " + histPriceDAO[i].getClosingDate().toString());
				logger.info("Closing Price : " + histPriceDAO[i].getClosingPrice());
				logger.info("LastUpdateDate : " + histPriceDAO[i].getLastupdatedate().toString());
				
				i++;
				if (i >= maxDateSize)
					break;
			}
		}
		catch(Exception e)
		{
			logger.info(">>>>>> StockDetailsService::getHistoricalPrices - An error occurred retrieving historical price data from JSON buffer ...");
			e.printStackTrace();
		}
		return histPriceDAO;
	}

	public void updateStockDetails(StockDetails stockDetails)
	{
		stockDetailsRepository.save(stockDetails);
	}

	public void addMovingAverageData(MovingAverageDAO movingAverage)
	{
		movingAverageRepository.save(movingAverage);
	}

	public void addHistoricalPriceData(HistPriceDAO histPriceDAO)
	{
		histPriceRepository.save(histPriceDAO);
	}

	public List<Stock> getOptionableStocks(String indValue)
	{
		return stockRepository.findByOptionsoffered(indValue);
	}

	/**
	 * @return the symbol
	 */
	public String getSymbol()
	{
		return symbol;
	}

	/**
	 * @param symbol the symbol to set
	 */
	public void setSymbol(String symbol)
	{
		this.symbol = symbol;
	}

	/**
	 * @return the function
	 */
	public String getFunction()
	{
		return function;
	}

	/**
	 * @param function the function to set
	 */
	public void setFunction(String function)
	{
		this.function = function;
	}

	/**
	 * @return the interval
	 */
	public String getInterval()
	{
		return interval;
	}

	/**
	 * @param interval the interval to set
	 */
	public void setInterval(String interval)
	{
		this.interval = interval;
	}

	/**
	 * @return the period
	 */
	public int getPeriod()
	{
		return period;
	}

	/**
	 * @param period the period to set
	 */
	public void setPeriod(int period)
	{
		this.period = period;
	}

	/**
	 * @return the apiKey
	 */
	public String getApiKey()
	{
		return apiKey;
	}

	/**
	 * @param apiKey the apiKey to set
	 */
	public void setApiKey(String apiKey)
	{
		this.apiKey = apiKey;
	}
}