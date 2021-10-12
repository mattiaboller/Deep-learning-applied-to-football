package com.company;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

//Script for scraping the website whoscored.com
public class Main {

    public static void main(String[] args) throws IOException {
        //Setting the driver executable
        System.setProperty("webdriver.chrome.driver", ".\\Driver\\chromedriver.exe");

        //Initiating your chromedriver
        WebDriver driver=new ChromeDriver();
        WebDriverWait wait = new WebDriverWait(driver, 60);

        //Open browser with desried URL
        driver.navigate().to("https://it.whoscored.com/");

        //Allow coockies
        String allowCookiesButton = "//*[@id=\"qcCmpButtons\"]/button[2]";
        wait.until(ExpectedConditions.elementToBeClickable(By.xpath(allowCookiesButton)));
        driver.findElement(By.xpath(allowCookiesButton)).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.xpath(allowCookiesButton)));

        //Championship List
        ArrayList<String> championships = new ArrayList<String>();
        championships.add("//*[@id=\"popular-tournaments-list\"]/li[1]/a"); //Premier League
        championships.add("//*[@id=\"popular-tournaments-list\"]/li[2]/a"); //Serie A
        championships.add("//*[@id=\"popular-tournaments-list\"]/li[3]/a"); //LaLiga
        championships.add("//*[@id=\"popular-tournaments-list\"]/li[4]/a"); //Bundesliga

        //Scrape data for every championship selected
        for (String championshipButton:championships) {
            //Go to championship page
            wait.until(ExpectedConditions.elementToBeClickable(By.xpath(championshipButton)));
            String championshipName = driver.findElement(By.xpath(championshipButton)).getText();
            driver.findElement(By.xpath(championshipButton)).click();

            //Go to matches list
            String matchesListButton = "//*[@id=\"sub-navigation\"]/ul/li[2]/a";
            wait.until(ExpectedConditions.elementToBeClickable(By.xpath(matchesListButton)));
            driver.findElement(By.xpath(matchesListButton)).click();
            scrapeChampionship(driver, wait, championshipName);

            driver.navigate().back();
            driver.navigate().back();
        }

        //Closing the browser
        driver.close();
    }

    static void scrapeChampionship(WebDriver driver, WebDriverWait wait, String championshipName) throws IOException {
        //Scrape data on every month for a Championship
        String changeMonthButton = "//*[@id=\"date-controller\"]/a[1]";
        String month = "//*[@id=\"date-config-toggle-button\"]/span[1]";
        String firstLine = "//*[@id=\"tournament-fixture\"]/tbody/tr[1]/th";
        scrapeMonth(driver, wait, driver.findElement(By.xpath(month)).getText(), championshipName);
        do {
            wait.until(ExpectedConditions.elementToBeClickable(By.xpath(changeMonthButton)));
            String oldText = driver.findElement(By.xpath(firstLine)).getText();
            driver.findElement(By.xpath(changeMonthButton)).click();
            //Wait until data updates
            wait.until(new ExpectedCondition<Boolean>() {
                public Boolean apply(WebDriver driver) {
                    WebElement firstLineElement = driver.findElement(By.xpath(firstLine));
                    String newText = firstLineElement.getText();
                    if(newText.equals(oldText))
                        return false;
                    else
                        return true;
                }
            });
            scrapeMonth(driver, wait, driver.findElement(By.xpath(month)).getText(), championshipName);
        } while(driver.findElement(By.xpath(changeMonthButton)).getAttribute("title").equals("View previous month"));
    }

    static void scrapeMonth(WebDriver driver, WebDriverWait wait, String month, String championshipName) throws IOException {
        //Get all the buttons that show the matches details
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("tournament-fixture")));
        List<WebElement> matchesDetailsButtons = driver.findElements(By.cssSelector(".match-link.match-report.rc"));

        for (int i=0; i<matchesDetailsButtons.size(); i++) {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("tournament-fixture")));
            WebElement element = driver.findElements(By.cssSelector(".match-link.match-report.rc")).get(i);
            wait.until(ExpectedConditions.elementToBeClickable(element));

            //Open the match details page in a new tab
            String link = element.getAttribute("href");
            ((JavascriptExecutor)driver).executeScript("window.open()");
            ArrayList<String> tabs = new ArrayList<String>(driver.getWindowHandles());
            driver.switchTo().window(tabs.get(1));
            driver.get(link);

            scrapeMatch(driver, wait, i, month, championshipName);
            driver.close();
            driver.switchTo().window(tabs.get(0));
        }
    }

    static void scrapeMatch(WebDriver driver, WebDriverWait wait, int id, String month, String championshipName) throws IOException {
        wait.until(ExpectedConditions.titleContains("Resoconto"));

        //Navigate to match centre page
        String matchCentreButton = "//*[@id=\"sub-navigation\"]/ul/li[4]/a";
        WebElement button = driver.findElement(By.xpath(matchCentreButton));
        wait.until(ExpectedConditions.elementToBeClickable(button));
        button.click();

        //Get matchData Json

        //Retry in case of fail click (It seems a bug of Selenium, sometimes te click doesn't work)
        try {
            wait.until(ExpectedConditions.titleContains("Live"));
        } catch(TimeoutException e){
            button.click();
        }
        wait.until(ExpectedConditions.titleContains("Live"));
        String source = driver.getPageSource();
        try {
            source = source.substring(source.indexOf("matchCentreData") + 18, source.indexOf("var matchCentreEventTypeJson")).trim();
        } catch (Exception e){
            button.click();
            wait.until(ExpectedConditions.titleContains("Live"));
            source = driver.getPageSource();
            source = source.substring(source.indexOf("matchCentreData") + 18, source.indexOf("var matchCentreEventTypeJson")).trim();
        }
        File file = new File(".\\Data\\WhoScored\\"+championshipName+"\\json "+month+" "+id+".txt");
        FileWriter fw = new FileWriter(file.getAbsoluteFile());
        BufferedWriter bw = new BufferedWriter(fw);
        bw.write(source);
        bw.close();
    }
}
