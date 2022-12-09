void call() {
    String redisCrName = "redis-sentinel"
    ArrayList redisPvc = sh(script:
            "oc -n ${NAMESPACE} get pvc -l app.kubernetes.io/name=redis-sentinel \
                    -o custom-columns=NAME:.metadata.name --no-headers",
            returnStdout: true).tokenize()
    String currentRedisStorageClass = sh(script:
            "oc -n ${NAMESPACE} get pvc ${redisPvc[0]} -o jsonpath='{.spec.storageClassName}'",
            returnStdout: true).trim()
    String platformStorageClass = sh(script:
            "helm get values registry-configuration -n ${NAMESPACE} | grep '^storageClass: ' | awk '{print \$2}'",
            returnStdout: true).trim()
    if (currentRedisStorageClass != platformStorageClass) {
        sh """
            oc -n ${NAMESPACE} get pvc -l app.kubernetes.io/name=redis-sentinel -o json | 
            jq 'del(.items[].metadata.resourceVersion,.items[].metadata.uid,.items[].metadata.managedFields,
                .items[].metadata.managedFields,.items[].status,.items[].metadata.creationTimestamp,
                .items[].metadata.annotations,.items[].spec.volumeMode,.items[].spec.volumeName)' | 
            jq --arg storageClass "${platformStorageClass}" '.items[].spec.storageClassName=\$storageClass' > redisPvc.json
            oc -n ${NAMESPACE} delete redisfailover ${redisCrName}
        """
        redisPvc.each { pvc ->
            sh """
                jq --arg pvcName "${pvc}" '.items[] | select(.metadata.name==\$pvcName)' redisPvc.json | \
                jq --arg pvcName "${pvc}-temp" '.metadata.name=\$pvcName' | \
                oc apply -f -
                oc -n ${NAMESPACE} process -f ./resources/JobMigrateDataBetweenPvc.yaml \
                    -p NAMESPACE=${NAMESPACE} \
                    -p SRC_PVC=${pvc} \
                    -p DEST_PVC=${pvc}-temp | \
                oc -n ${NAMESPACE} create -f -
                JOB_NAME="rsync-to-${pvc}-temp"
                while [[ `oc -n ${NAMESPACE} get job \${JOB_NAME} -o jsonpath='{.status.succeeded}'` != 1 ]]; do
                    echo "Job \${JOB_NAME} is still running or didn't start yet"
                    sleep 10
                done
                oc -n ${NAMESPACE} delete job \${JOB_NAME}
                oc -n ${NAMESPACE} delete pvc ${pvc}
                jq --arg pvcName "${pvc}" '.items[] | select(.metadata.name==\$pvcName)' redisPvc.json | \
                oc apply -f -
                oc -n ${NAMESPACE} process -f ./resources/JobMigrateDataBetweenPvc.yaml \
                    -p NAMESPACE=${NAMESPACE} \
                    -p SRC_PVC=${pvc}-temp \
                    -p DEST_PVC=${pvc} | \
                oc -n ${NAMESPACE} create -f -
                JOB_NAME="rsync-to-${pvc}"
                while [[ `oc -n ${NAMESPACE} get job \${JOB_NAME} -o jsonpath='{.status.succeeded}'` != 1 ]]; do
                    echo "Job \${JOB_NAME} is still running or didn't start yet"
                    sleep 10
                done
                oc -n ${NAMESPACE} delete job \${JOB_NAME}
                oc -n ${NAMESPACE} delete pvc ${pvc}-temp
            """
        }
        sh "rm redis*.json"
    }
}

return this;
