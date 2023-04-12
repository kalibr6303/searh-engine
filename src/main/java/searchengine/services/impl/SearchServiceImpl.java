package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.developer.Snippet;
import searchengine.dto.Response.SearchResponse;
import searchengine.dto.SearchDto;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.morphology.Morphology;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.SearchService;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@RequiredArgsConstructor

public class SearchServiceImpl implements SearchService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final Morphology morphology;
    private final Snippet snippet;
    private final SitesList sitesList;


    public SearchResponse allSiteSearch(String word, int offset, int limit) throws IOException {
        SearchResponse searchResponse = new SearchResponse();
        if (word.isEmpty() || !word.matches("[а-яА-Я\\s]+")) {
            searchResponse.setResult(false);
            searchResponse.setError("Задан пустой поисковый запрос");
            return searchResponse;
        }
        List<searchengine.config.Site> urlList = sitesList.getSites();
        List<SearchDto> allSearchDto = new ArrayList<>();
        for (searchengine.config.Site s : urlList) {
            List<Integer> lemmasOfBase = getRequestListSite(word, s.getUrl());
            List<SearchDto> searchDtoOfSite = getRequestResponse(lemmasOfBase, word, offset, limit);
            if (searchDtoOfSite != null) allSearchDto.addAll(searchDtoOfSite);
        }
        Collections.sort(allSearchDto, (o1, o2) -> {
            return o2.getRelevance().compareTo(o1.getRelevance());
        });
        searchResponse.setResult(true);
        searchResponse.setData(allSearchDto);
        searchResponse.setCount(allSearchDto.size());


        return searchResponse;
    }


    public SearchResponse siteSearch(String word, String url, int offset, int limit)  {
        SearchResponse searchResponse = new SearchResponse();
        if (word.isEmpty() || !word.matches("[а-яА-Я\\s]+")) {
            searchResponse.setResult(false);
            searchResponse.setError("Задан пустой поисковый запрос");
            return searchResponse;
        }
        List<Integer> lemmasOfBase = getRequestListSite(word, url);
        List<SearchDto> searchDtoOfSite = getRequestResponse(lemmasOfBase, word, offset, limit);
        searchResponse.setResult(true);
        searchResponse.setData(searchDtoOfSite);
        if (searchDtoOfSite != null) searchResponse.setCount(searchDtoOfSite.size());
        else searchResponse.setCount(0);
        return searchResponse;
    }

    // TODO: 11.02.2023 Получаем  сортированный список лемм по значению frequency, имеющихся в базе
    private List<Integer> getRequestListSite(String query, String url) {
        HashMap<String, Integer> store = morphology.getLemmaList(query);

        List<String> request = new ArrayList<>();
        store.entrySet().forEach(s -> request.add(s.getKey()));
        HashMap<Integer, Integer> lemmaFrequency = new HashMap<>();
        List<Integer> idLemmasSort = new ArrayList<>();
        if (url != null) {
            Site site = siteRepository.findByUrl(url);
            request.forEach(l -> {
                Lemma lemma = lemmaRepository.findLemmaByLemmaAndSite(l, site);
                if (lemma != null){
                    lemmaFrequency.put(lemma.getId(), lemma.getFrequency());
                }
            });


            if (lemmaFrequency != null) {  //сортируем по значению (frequency)
                lemmaFrequency.entrySet()
                        .stream()
                        .sorted(Map.Entry.comparingByValue())
                        .forEach(s -> idLemmasSort.add(s.getKey()));
            } else return null;
        }

        return idLemmasSort;
    }


    private List<SearchDto> getRequestResponse(List<Integer> lemmasOfBase, String word, int offset, int limit) {
        HashMap<Integer, Float> listLemmasAccordingToRequest = getFilteredPage(lemmasOfBase, word);
        if (listLemmasAccordingToRequest == null) return null;
        List<SearchDto> listOfSearchDto = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        listLemmasAccordingToRequest.entrySet().stream() // сортируем HashMap по уменьшению R(rel)
                .sorted(Map.Entry.<Integer, Float>comparingByValue()
                        .reversed())
                .forEach(s -> {
                    count.getAndIncrement();

                    SearchDto searchDto = new SearchDto();
                    Optional<Page> page = pageRepository.findById(s.getKey());
                    String content = page.get().getContent();
                    if (snippet.getSnippet(content, word) != null) {
                        searchDto.setRelevance(s.getValue());
                        String snip = snippet.getSnippet(content, word);
                        if (snip != null) searchDto.setSnippet(snip);
                        searchDto.setUri(page.get().getPath());
                        searchDto.setTitle(getTitleOfPage(page.get().getContent()));
                        searchDto.setSite(page.get().getSite().getUrl());
                        searchDto.setSiteName(page.get().getSite().getName());
                        listOfSearchDto.add(searchDto);
                    }
                });

        return trimListObject(listOfSearchDto, offset, limit);
    }


    // todo Получаем список страниц соответствующей последней(редкой) лемме
    private List<Integer> getListLemmasRequest(List<Integer> lemmas) {

        List<Integer> idOneOfPage = new ArrayList<>();
        int lemmaOne = 0;
        if (!lemmas.isEmpty()) lemmaOne = lemmas.get(0);
        Lemma lemma = lemmaRepository.getReferenceById(lemmaOne);
        List<Index> index = indexRepository.getIndexByLemma(lemma);
        index.forEach(i -> idOneOfPage.add(i.getPage().getId()));

        return idOneOfPage;
    }


    // todo Фильтруем спимок страниц, ислючая страницы где не содержаться  все леммы согласно запросу
    private HashMap<Integer, Float> getFilteredPage(List<Integer> lemmasInBase, String word) {

        List<Integer> pagesForOneLemma = getListLemmasRequest(lemmasInBase);
        HashMap<String, Integer> store = morphology.getLemmaList(word);

        if (lemmasInBase.isEmpty() || lemmasInBase.size() != store.size()) return null;

        HashMap<Integer, Float> pageRanks = new HashMap<>();
        pagesForOneLemma.forEach(p -> {
            for (Integer l : lemmasInBase){
                if (isOnIndex(p, l) == 0) {
                    if (pageRanks.containsKey(p)) pageRanks.remove(p);
                    break;
                }
                else if (pageRanks.containsKey(p)) pageRanks.put(p, pageRanks.get(p) + isOnIndex(p, l));
                else pageRanks.put(p, isOnIndex(p, l));
            }

        });


        if (pageRanks.size() == 0) return null;
        Float maxEntry = pageRanks.entrySet() // рассчитываем абсолютную и относительную релевантность
                .stream().max(Comparator.comparing(Map.Entry::getValue))
                .orElse(null).getValue();// сохраняем в значении ключа относительную релевантность
        pageRanks.entrySet().stream().forEach(s -> pageRanks.put(s.getKey(), s.getValue() / maxEntry));

        return pageRanks;
    }



    private float isOnIndex(int page, int lemma) {
        Lemma word = lemmaRepository.findLemmaById(lemma);
        Page page1 = pageRepository.findById(page);
        Index index = indexRepository.getIndexByLemmaAndPage(word, page1);
        if (index == null) return 0;
        else return index.getRanks();
    }


    private String getTitleOfPage(String text) {
        String regex = "<title>[\\W\\w\\s]+</title>";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        String titleResult = null;
        if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String title = text.substring(start, end);
            titleResult = title.replaceAll("[^а-яА-Я\\s]+", " ");
        }
        return titleResult;

    }

    private List<SearchDto> trimListObject(List<SearchDto> list, int offset, int limit) {
        int count = list.size();
        int last = count - offset;
        if (last > 0) {
            for (int i = 0; i < offset; i++) {
                list.remove(i);
            }
        }
        int number = last - limit;
        if (number > 0) {
            for (int i = count - 1; i >= limit; i--) {
                list.remove(i);
            }
        }
        return list;
    }

}