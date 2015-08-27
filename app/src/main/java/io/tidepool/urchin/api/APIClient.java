package io.tidepool.urchin.api;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Cache;
import com.android.volley.Network;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.BasicNetwork;
import com.android.volley.toolbox.DiskBasedCache;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.StringRequest;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import io.realm.Realm;
import io.realm.RealmList;
import io.realm.RealmObject;
import io.realm.RealmResults;
import io.tidepool.urchin.data.Note;
import io.tidepool.urchin.data.Profile;
import io.tidepool.urchin.data.RealmString;
import io.tidepool.urchin.data.Session;
import io.tidepool.urchin.data.User;

/**
 * Created by Brian King on 8/25/15.
 */
public class APIClient {

    public static final String PRODUCTION = "Production";
    public static final String DEVELOPMENT = "Development";
    public static final String STAGING = "Staging";

    private static final String LOG_TAG = "APIClient";

    // Date format for most things,
    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSSZ";

    // Date format for messages
    public static final String MESSAGE_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";

    // Header label for the session token
    private static final String HEADER_SESSION_ID = "x-tidepool-session-token";

    // Key into the shared preferences database for our own preferences
    private static final String PREFS_KEY = "APIClient";

    // Map of server names to base URLs
    private static final Map<String, URL> __servers;

    // RequestQueue our requests will be made on
    private RequestQueue _requestQueue;

    // Base URL for network requests
    private URL _baseURL;

    // Context used to create us
    private Context _context;

    // Static initialization
    static {
        __servers = new HashMap<>();
        try {
            __servers.put(PRODUCTION, new URL("https://api.tidepool.io"));
            __servers.put(DEVELOPMENT, new URL("https://devel-api.tidepool.io"));
            __servers.put(STAGING, new URL("https://staging-api.tidepool.io"));

        } catch (MalformedURLException e) {
            // Should never happen
        }
    }

    /**
     * Constructor
     *
     * @param context Context
     * @param server Server to connect to, one of Production, Development or Staging
     */
    public APIClient(Context context, String server) {
        _context = context;
        _baseURL = __servers.get(server);

        // Set up the disk cache for caching responses
        Cache cache = new DiskBasedCache(context.getCacheDir(), 1024*1024);

        // Set up the HTTPURLConnection network stack
        Network network = new BasicNetwork(new HurlStack());

        // Create the request queue using the cache and network we just created
        _requestQueue = new RequestQueue(cache, network);
        _requestQueue.start();
    }

    /**
     * Sets the server the API client will connect to. Valid servers are:
     * <ul>
     *     <li>Production</li>
     *     <li>Development</li>
     *     <li>Staging</li>
     * </ul>
     * @param serverType String with one of the above values used to set the server
     */
    public void setServer(String serverType) {
        URL url = __servers.get(serverType);
        if ( url == null ) {
            Log.e(LOG_TAG, "No server called " + serverType + " found in map");
        } else {
            _baseURL = url;
        }
    }

    /**
     * Returns the current user. Only valid if authenticated.
     * @return the current user
     */
    public User getUser() {
        Realm realm = Realm.getInstance(_context);
        RealmResults<Session> results = realm.where(Session.class)
                .findAll();
        if ( results.size() == 0 ) {
            return null;
        }

        return results.first().getUser();
    }

    /**
     * Returns the session ID used for this client.
     * @return the session ID, or null if not authenticated
     */
    public String getSessionId() {
        Realm realm = Realm.getInstance(_context);
        RealmResults<Session> results = realm.where(Session.class)
                .findAll();
        if ( results.size() == 0 ) {
            return null;
        }

        Session session = results.first();
        return session.getSessionId();
    }

    public static abstract class SignInListener {
        /**
         * Called when the sign-in request returns. This method will capture the session ID
         * from the headers returned in the sign-in request, and use it in all subsequent
         * requests.
         *
         * @param user User object, if the sign-in was successful
         * @param exception Exception if the sign-in was not successful
         */
        public abstract void signInComplete(User user, Exception exception);
    }

    /**
     * Signs in a user. The listener will be called with the user object, or an error if the sign
     * in failed.
     *
     * @param username Username
     * @param password Password
     * @param listener Listener to receive the result
     * @return a Request object, which may be canceled.
     */
    public Request signIn(String username, String password, final SignInListener listener) {
        // Our session is no longer valid. Get rid of it.
        Realm realm = Realm.getInstance(_context);
        RealmResults<Session> sessions = realm.allObjects(Session.class);
        for ( Session s : sessions ) {
            s.removeFromRealm();
        }

        // Create the authorization header with base64-encoded username:password
        final Map<String, String> headers = getHeaders();
        String authString = username + ":" + password;
        String base64string = Base64.encodeToString(authString.getBytes(), Base64.NO_WRAP);
        headers.put("Authorization", "Basic " + base64string);

        // Build the URL for login
        String url = null;
        try {
            url = new URL(getBaseURL(), "/auth/login").toString();
        } catch (MalformedURLException e) {
            listener.signInComplete(null, e);
            return null;
        }

        // Create the request. We want to set and get the headers, so need to override
        // parseNetworkResponse and getHeaders in the request object.
        StringRequest req = new StringRequest(Request.Method.POST, url, new Response.Listener<String>() {

            // Listener overrides
            @Override
            public void onResponse(String response) {
                Realm realm = Realm.getInstance(_context);
                Log.d(LOG_TAG, "Login success: " + response);
                RealmResults<Session> sessions = realm.where(Session.class).findAll();
                if ( sessions.size() == 0 ) {
                    // No session ID found
                    listener.signInComplete(null, new Exception("No session ID returned in headers"));
                    return;
                }

                Session s = sessions.first();

                Gson gson = getGson(DEFAULT_DATE_FORMAT);
                User user = gson.fromJson(response, User.class);
                realm.beginTransaction();
                User copiedUser = realm.copyToRealm(user);
                s.setUser(copiedUser);
                realm.commitTransaction();
                listener.signInComplete(user, null);
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.d(LOG_TAG, "Login failure: " + error);
                listener.signInComplete(null, error);
            }
        }) {
            // Request overrides

            @Override
            protected Response<String> parseNetworkResponse(NetworkResponse response) {
                String sessionId = response.headers.get(HEADER_SESSION_ID);
                if ( sessionId != null ) {
                    Realm realm = Realm.getInstance(_context);
                    // Create the session in the database
                    realm.beginTransaction();
                    Session s = realm.createObject(Session.class);
                    s.setSessionId(sessionId);
                    s.setKey(Session.SESSION_KEY);
                    realm.commitTransaction();
                }
                return super.parseNetworkResponse(response);
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                return headers;
            }
        };

        // Tag the request with our context so they all can be removed if the activity goes away
        req.setTag(_context);
        _requestQueue.add(req);
        return req;
    }

    /**
     * Returns a GSON instance used for working with Realm and GSON together, and a specific
     * date format for date fields.
     *
     * @param dateFormat Date format string to use when parsing dates
     * @return a GSON instance for use with Realm and the specified date format
     */
    public static Gson getGson(String dateFormat) {
        // Make a custom Gson instance, with a custom TypeAdapter for each wrapper object.
        // In this instance we only have RealmList<RealmString> as a a wrapper for RealmList<String>
        Type token = new TypeToken<RealmList<RealmString>>(){}.getType();
        Gson gson = new GsonBuilder()
                .setExclusionStrategies(new ExclusionStrategy() {
                    @Override
                    public boolean shouldSkipField(FieldAttributes f) {
                        return f.getDeclaringClass().equals(RealmObject.class);
                    }

                    @Override
                    public boolean shouldSkipClass(Class<?> clazz) {
                        return false;
                    }
                })
                .registerTypeAdapter(token, new TypeAdapter<RealmList<RealmString>>() {

                    @Override
                    public void write(JsonWriter out, RealmList<RealmString> value) throws IOException {
                        // Ignore
                    }

                    @Override
                    public RealmList<RealmString> read(JsonReader in) throws IOException {
                        RealmList<RealmString> list = new RealmList<RealmString>();
                        in.beginArray();
                        while (in.hasNext()) {
                            list.add(new RealmString(in.nextString()));
                        }
                        in.endArray();
                        return list;
                    }
                })
                .setDateFormat(dateFormat)
                .create();

        return gson;
    }


    public static abstract class ViewableUserIdsListener {
        public abstract void fetchComplete(RealmList<RealmString> userIds, Exception error);
    }
    public Request getViewableUserIds(final ViewableUserIdsListener listener) {
        // Build the URL for login
        String url = null;
        try {
            url = new URL(getBaseURL(), "/access/groups/" + getUser().getUserid()).toString();
        } catch (MalformedURLException e) {
            listener.fetchComplete(null, e);
            return null;
        }

        StringRequest req = new StringRequest(Request.Method.GET, url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                try {
                    JSONObject jsonObject = new JSONObject(response);
                    RealmList<RealmString> userIds = new RealmList<>();
                    Iterator iter = jsonObject.keys();

                    Realm realm = Realm.getInstance(_context);
                    realm.beginTransaction();

                    while ( iter.hasNext() ) {
                        String viewableId = (String)iter.next();
                        userIds.add(realm.copyToRealm(new RealmString(viewableId)));
                    }

                    // Put the IDs into the database
                    User user = getUser();
                    user.setViewableUserIds(userIds);

                    realm.commitTransaction();

                    listener.fetchComplete(userIds, null);
                } catch (JSONException e) {
                    listener.fetchComplete(null, e);
                }
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                listener.fetchComplete(null, error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                return APIClient.this.getHeaders();
            }
        };

        _requestQueue.add(req);
        return req;
    }

    public static abstract class ProfileListener {
        public abstract void profileReceived(Profile profile, Exception error);
    }

    public Request getProfileForUserId(final String userId, final ProfileListener listener) {
        // Build the URL for getProfile
        String url = null;
        try {
            url = new URL(getBaseURL(), "/metadata/" + userId + "/profile").toString();
        } catch (MalformedURLException e) {
            listener.profileReceived(null, e);
            return null;
        }

        StringRequest req = new StringRequest(url, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {
                Gson gson = getGson(DEFAULT_DATE_FORMAT);
                Profile fakeProfile = gson.fromJson(response, Profile.class);
                fakeProfile.setUserId(userId);

                Log.d(LOG_TAG, "Profile response: " + response);
                Realm realm = Realm.getInstance(_context);
                realm.beginTransaction();
                Profile profile = realm.copyToRealmOrUpdate(fakeProfile);
                // Create a user with this profile and add / update it
                User user = realm.where(User.class).equalTo("userid", userId).findFirst();
                if ( user == null ) {
                    user = realm.createObject(User.class);
                    user.setUserid(userId);
                }
                user.setProfile(profile);
                realm.commitTransaction();

                listener.profileReceived(profile, null);
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(LOG_TAG, "Profile error: " + error);
                listener.profileReceived(null, error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                return APIClient.this.getHeaders();
            }
        };

        _requestQueue.add(req);
        return req;
    }

    public static abstract class NotesListener {
        public abstract void notesReceived(RealmList<Note> notes, Exception error);
    }
    public Request getNotes(final String userId, final Date fromDate, final Date toDate, final NotesListener listener) {
        String url = null;
        try {
            DateFormat df = new SimpleDateFormat(DEFAULT_DATE_FORMAT, Locale.US);
            String extension = "/message/notes/" + userId + "?starttime=" +
                    URLEncoder.encode(df.format(fromDate), "utf-8") +
                    "&endtime=" +
                    URLEncoder.encode(df.format(toDate), "utf-8");

            url = new URL(getBaseURL(), extension).toString();
        } catch (MalformedURLException e) {
            listener.notesReceived(null, e);
            return null;
        } catch (UnsupportedEncodingException e) {
            listener.notesReceived(null, e);
            return null;
        }

        StringRequest req = new StringRequest(url, new Response.Listener<String>() {
            @Override
            public void onResponse(String json) {
                // Returned JSON is an object array called "messages"
                Realm realm = Realm.getInstance(_context);
                RealmList<Note> noteList = new RealmList<>();
                realm.beginTransaction();
                // Odd date format in the messages
                Gson gson = getGson(MESSAGE_DATE_FORMAT);
                try {
                    JSONObject obj = new JSONObject(json);
                    JSONArray messages = obj.getJSONArray("messages");

                    for ( int i = 0; i < messages.length(); i++ ) {
                        String msgJson = messages.getString(i);
                        Note note = gson.fromJson(msgJson, Note.class);
                        note.setUserid(userId);
                        note = realm.copyToRealmOrUpdate(note);
                        noteList.add(note);
                    }
                } catch (JSONException e) {
                    realm.cancelTransaction();
                    listener.notesReceived(null, e);
                }

                realm.commitTransaction();
                listener.notesReceived(noteList, null);
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                listener.notesReceived(null, error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                return APIClient.this.getHeaders();
            }
        };

        _requestQueue.add(req);
        return req;
    }

    protected URL getBaseURL() {
        return _baseURL;
    }

    /**
     * Returns a map with the HTTP headers. This will include the session ID if present.
     *
     * @return A map with the HTTP headers for a request.
     */
    protected Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        String sessionId = getSessionId();
        if ( sessionId != null ) {
            headers.put(HEADER_SESSION_ID, sessionId);
        }
        return headers;
    }
}
