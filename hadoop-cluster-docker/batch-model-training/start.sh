export SPARK_APPLICATION_ARGS="${APP_ARGS_CSV_FILE_PATH} ${APP_EVENTS_CSV_FILE_PATH}"

echo "App is sleeping"
# sleep 30

echo "Running app"
sh /template.sh

echo "DONE"