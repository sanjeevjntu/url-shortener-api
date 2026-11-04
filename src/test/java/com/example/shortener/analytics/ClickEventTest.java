package com.example.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickEventTest {

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "https://News.Example.com/a?b=1, news.example.com",
                "NULL, direct",
                "'', direct",
                "not a url, direct"
            })
    void keepsOnlyTheReferrerHost(String referer, String expected) {
        assertThat(ClickEvent.referrerHost(referer)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NULL",
            value = {
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0 | Edge",
                "Mozilla/5.0 (X11; Linux) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 | Chrome",
                "Mozilla/5.0 (Macintosh) AppleWebKit/605.1 Version/17.0 Safari/605.1 | Safari",
                "Mozilla/5.0 (X11; rv:121.0) Gecko/20100101 Firefox/121.0 | Firefox",
                "Mozilla/5.0 Chrome/120.0 Safari/537.36 OPR/105.0 | Opera",
                "Googlebot/2.1 (+http://www.google.com/bot.html) | bot",
                "curl/8.5.0 | curl",
                "NULL | unknown",
                "SomethingElse/1.0 | other"
            })
    void classifiesBrowserFamily(String userAgent, String family) {
        assertThat(ClickEvent.browserFamily(userAgent)).isEqualTo(family);
    }
}
