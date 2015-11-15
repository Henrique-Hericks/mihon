package eu.kanade.mangafeed.ui.reader;

import android.os.Bundle;

import java.io.File;
import java.util.List;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;
import eu.kanade.mangafeed.data.database.DatabaseHelper;
import eu.kanade.mangafeed.data.database.models.Chapter;
import eu.kanade.mangafeed.data.database.models.Manga;
import eu.kanade.mangafeed.data.download.DownloadManager;
import eu.kanade.mangafeed.data.preference.PreferencesHelper;
import eu.kanade.mangafeed.data.source.base.Source;
import eu.kanade.mangafeed.data.source.model.Page;
import eu.kanade.mangafeed.event.SourceMangaChapterEvent;
import eu.kanade.mangafeed.ui.base.presenter.BasePresenter;
import eu.kanade.mangafeed.util.EventBusHook;
import icepick.State;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class ReaderPresenter extends BasePresenter<ReaderActivity> {

    @Inject PreferencesHelper prefs;
    @Inject DatabaseHelper db;
    @Inject DownloadManager downloadManager;

    private Source source;
    private Manga manga;
    private Chapter chapter;
    private Chapter nextChapter;
    private Chapter previousChapter;
    private List<Page> pageList;
    private boolean isDownloaded;
    @State int currentPage;

    private Subscription nextChapterSubscription;
    private Subscription previousChapterSubscription;

    private static final int GET_PAGE_LIST = 1;
    private static final int GET_PAGE_IMAGES = 2;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        restartableLatestCache(GET_PAGE_LIST,
                () -> getPageListObservable()
                        .doOnNext(pages -> pageList = pages)
                        .doOnCompleted(this::getAdjacentChapters)
                        .doOnCompleted(() -> start(GET_PAGE_IMAGES)),
                (view, pages) -> {
                    view.onPageListReady(pages);
                    if (currentPage != 0)
                        view.setSelectedPage(currentPage);
                },
                (view, error) -> Timber.e("An error occurred while downloading page list"));

        restartableReplay(GET_PAGE_IMAGES,
                this::getPageImagesObservable,
                (view, page) -> {},
                (view, error) -> Timber.e("An error occurred while downloading an image"));

    }

    @Override
    protected void onTakeView(ReaderActivity view) {
        super.onTakeView(view);
        registerForStickyEvents();
    }

    @Override
    protected void onDropView() {
        unregisterForEvents();
        super.onDropView();
    }

    @Override
    protected void onDestroy() {
        onChapterChange();
        super.onDestroy();
    }

    @EventBusHook
    public void onEventMainThread(SourceMangaChapterEvent event) {
        EventBus.getDefault().removeStickyEvent(event);
        source = event.getSource();
        manga = event.getManga();
        loadChapter(event.getChapter());
    }

    private void loadChapter(Chapter chapter) {
        this.chapter = chapter;
        isDownloaded = isChapterDownloaded(chapter);
        if (chapter.last_page_read != 0 && !chapter.read)
            currentPage = chapter.last_page_read;
        else
            currentPage = 0;

        // Reset next and previous chapter. They have to be fetched again
        nextChapter = null;
        previousChapter = null;

        start(GET_PAGE_LIST);
    }

    private void onChapterChange() {
        if (!isDownloaded)
            source.savePageList(chapter.url, pageList);
        saveChapterProgress();
    }

    private Observable<List<Page>> getPageListObservable() {
        if (!isDownloaded)
            return source.getCachedPageListOrPullFromNetwork(chapter.url)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread());
        else
            return Observable.just(downloadManager.getSavedPageList(source, manga, chapter));
    }

    private Observable<Page> getPageImagesObservable() {
        Observable<Page> pages;

        if (!isDownloaded) {
            pages = Observable
                    .merge(Observable.from(pageList).filter(page -> page.getImageUrl() != null),
                            source.getRemainingImageUrlsFromPageList(pageList))
                    .flatMap(source::getCachedImage);
        } else {
            File chapterDir = downloadManager.getAbsoluteChapterDirectory(source, manga, chapter);

            pages = Observable.from(pageList)
                    .flatMap(page -> downloadManager.getDownloadedImage(page, source, chapterDir));
        }
        return pages
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    public void retryPage(Page page) {

    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    private void saveChapterProgress() {
        chapter.last_page_read = currentPage;
        if (currentPage == pageList.size() - 1) {
            chapter.read = true;
        }
        db.insertChapter(chapter).executeAsBlocking();
    }

    private void getAdjacentChapters() {
        if (nextChapterSubscription != null)
            remove(nextChapterSubscription);

        add(nextChapterSubscription = db.getNextChapter(chapter).createObservable()
                .flatMap(Observable::from)
                .subscribeOn(Schedulers.io())
                .subscribe(result -> nextChapter = result));

        if (previousChapterSubscription != null)
            remove(previousChapterSubscription);

        add(previousChapterSubscription = db.getPreviousChapter(chapter).createObservable()
                .flatMap(Observable::from)
                .subscribeOn(Schedulers.io())
                .subscribe(result -> previousChapter = result));
    }

    public boolean isChapterDownloaded(Chapter chapter) {
        File dir = downloadManager.getAbsoluteChapterDirectory(source, manga, chapter);
        List<Page> pageList = downloadManager.getSavedPageList(source, manga, chapter);

        return pageList != null && pageList.size() + 1 == dir.listFiles().length;
    }

    public void loadNextChapter() {
        if (nextChapter != null) {
            onChapterChange();
            loadChapter(nextChapter);
        }
    }

    public void loadPreviousChapter() {
        if (previousChapter != null) {
            onChapterChange();
            loadChapter(previousChapter);
        }
    }
}
