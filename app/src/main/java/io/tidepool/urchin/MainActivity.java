package io.tidepool.urchin;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import io.tidepool.urchin.io.tidepool.urchin.api.APIClient;
import io.tidepool.urchin.io.tidepool.urchin.api.User;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "MainActivity";

    private APIClient _apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        _apiClient = new APIClient(this, "Production");

        _apiClient.signIn("larry@dufflite.com", "larryAtDL", new APIClient.SignInListener() {
            @Override
            public void signInComplete(User user, Exception exception) {
                Log.d(LOG_TAG, "signInComplete. User: " + user + " Exception: " + exception);
            }
        });

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
