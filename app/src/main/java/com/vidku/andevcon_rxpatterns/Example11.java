package com.vidku.andevcon_rxpatterns;

import com.google.common.collect.Iterables;
import com.google.gson.annotations.Expose;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.http.GET;
import retrofit.http.Path;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * Example of using concat() to choose between cache and network
 * based on expiration.
 *
 * I'm basing this code on Example 1 and adding the concat() operator to demonstrate
 * one way to use this pattern. One may either concat an observable that emits 0 or
 * more events later than the cache expiration OR ELSE concat two observables using
 * the first() operator with a function to test the cache expiration on each.
 *
 * This has become a popular paradigm for combining observables from your
 * reactive database and Retrofit.
 *
 * Here's a great blog post from my friend Dan Lew back in Minneapolis:
 * http://blog.danlew.net/2015/06/22/loading-data-from-multiple-sources-with-rxjava/
 *
 * Created by colin on 7/26/15.
 */
public class Example11 extends Activity {


    /** The GitHub REST API Endpoint */
    public final static String GITHUB_BASE_URL = "https://api.github.com";

    public static final int CACHE_EXP_IN_SECS = 60;

    /** Set this variable to your GitHub personal access token */
    /* public final static String GITHUB_PERSONAL_ACCESS_TOKEN = "XXX"; */

    private GitHubClient mGitHubClient;
    private ListView mListView;
    private String mGistVisible = "none";
    private List<Gist> mGistList;

    private AdapterView.OnItemClickListener gistClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            TextView tv = (TextView) view.findViewById(R.id.hiddenIdTextView);
            if (tv!=null) {
                String gistName = tv.getText().toString();
                mGitHubClient.gist(gistName)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new Action1<GistDetail>() {
                            @Override
                            public void call(GistDetail gistDetail) {
                                displayFileList(gistDetail);
                            }
                        }, new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                throwable.printStackTrace();
                            }
                        });

                mGistVisible = gistName;
                displayHomeAsUp(true); // just hacking everything together with a single activity for simplicity
            }
        }
    };

    /**
     * Retrofit interface to GitHub API methods
     */
    public interface GitHubClient {
        @GET("/gists")
        Observable<List<Gist>> gists();

        @GET("/gists/{id}")
        Observable<GistDetail> gist(@Path("id") String id);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_example1);
        mListView = (ListView) findViewById(R.id.listView);
        createGithubClient();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (Secrets.GITHUB_PERSONAL_ACCESS_TOKEN.equals("XXX")) {
            Toast.makeText(getApplicationContext(), "GitHub Personal Access Token is Unset!", Toast.LENGTH_LONG).show();
        }

        Observable.create(new Observable.OnSubscribe<List<Gist>>() {
            @Override
            public void call(Subscriber<? super List<Gist>> subscriber) {
                long cacheExpiration = System.currentTimeMillis();
                cacheExpiration -= CACHE_EXP_IN_SECS * 1000;

                // pretend mGistList is coming from the database here
                if (mGistList!=null &&
                        mGistList.size()>0 &&
                        mGistList.get(0).cachedTime > cacheExpiration) {
                    subscriber.onNext(mGistList);
                }
                subscriber.onCompleted();
            }
        })
            // remember concat and concatWith enforce the order
            // unlike merge and mergeWith
            // if you want cached events always followed by network events, use merge
            .concatWith(mGitHubClient.gists())
            .first()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(new Action1<List<Gist>>() {
                @Override
                public void call(List<Gist> gists) {
                    gists.get(0).cachedTime = System.currentTimeMillis();
                    mGistList = gists;
                    displayGistList(gists);
                    mListView.setOnItemClickListener(gistClickListener);
                }
            }, new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        throwable.printStackTrace();
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (mGistVisible.equals("none")) {
            super.onBackPressed();
        } else {
            if (mGistList!=null) {
                displayGistList(mGistList);
            }
            mGistVisible = "none";

            displayHomeAsUp(false);
        }
    }

    private void createGithubClient() {
        if (mGitHubClient == null) {
            mGitHubClient = new RestAdapter.Builder()
                    .setRequestInterceptor(new RequestInterceptor() {
                        @Override
                        public void intercept(RequestFacade request) {
                            request.addHeader("Authorization", "token " + Secrets.GITHUB_PERSONAL_ACCESS_TOKEN);
                        }
                    })
                    .setEndpoint(GITHUB_BASE_URL)
                    .setLogLevel(RestAdapter.LogLevel.HEADERS).build()
                    .create(GitHubClient.class);
        }
    }

    public void displayGistList(final List<Gist> gists) {
        if (gists.size()>0 && mListView!=null) {
            mListView.setAdapter(new GistAdapter(Example11.this, gists));
        }
    }

    public void displayFileList(final GistDetail gistDetail) {
        if (gistDetail.getFiles().size()>0 && mListView!=null) {
            mListView.setAdapter(new GistAdapter(Example11.this, gistDetail));
        }
    }

    void displayHomeAsUp(Boolean value) {
        ActionBar actionBar = getActionBar();
        if (actionBar!=null) {
            actionBar.setDisplayHomeAsUpEnabled(value);
        }
    }

    private class GistAdapter extends BaseAdapter {

        private Context mContext;
        private List<Gist> mGists;
        private Map<String, GistFile> mGistFileMap;

        public GistAdapter(Context context, List<Gist> gists) {
            mContext = context;
            mGists = gists;
        }

        public GistAdapter(Context context, GistDetail gistDetail) {
            mContext = context;
            mGistFileMap = gistDetail.getFiles();
        }

        @Override
        public int getCount() {
            if (mGistFileMap==null) {
                return mGists.size();
            } else {
                return mGistFileMap.size();
            }
        }

        @Override
        public Object getItem(int i) {
            if (mGistFileMap==null) {
                return mGists.get(i);
            } else {
                String key = Iterables.get(mGistFileMap.keySet(), i);
                return mGistFileMap.get(key);
            }
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            GistHolder holder;
            if (view != null) {
                holder = (GistHolder) view.getTag();
            } else {
                view = LayoutInflater.from(mContext).inflate(R.layout.row_gist, viewGroup, false);
                holder = new GistHolder(view);
                view.setTag(holder);
            }

            if (mGistFileMap == null) {
                Gist g = mGists.get(i);

                holder.title.setText(g.getHtml_url());
                holder.id.setText(g.getId());
            } else {
                GistFile gistFile = (GistFile) getItem(i);

                holder.title.setText(gistFile.getContent());
                holder.id.setText(gistFile.getContent());
            }

            return view;
        }

        private class GistHolder {

            TextView title;
            TextView id;

            GistHolder(View view) {
                title = (TextView) view.findViewById(R.id.gistTextView);
                id = (TextView) view.findViewById(R.id.hiddenIdTextView);
            }
        }
    }

    private class Gist {
        @Expose
        private String id;

        @Expose
        private String description;

        @Expose
        private String html_url;

        public long cachedTime;

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public String getHtml_url() {
            return html_url;
        }
    }

    private class GistDetail {
        @Expose
        private Map<String, GistFile> files;

        public Map<String, GistFile> getFiles() {
            return files;
        }
    }

    private class GistFile {
        @Expose
        private String content;

        public String getContent() {
            return content;
        }
    }


}
