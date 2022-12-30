void call() {
    int valuesReplicas = sh(script:
            "helm get values registry-nodes -n ${NAMESPACE} | awk '(NR>1)' | grep redis: -A 4 | grep replicas | awk '{print \$2}'",
            returnStdout: true).trim()
    int pvcCount = sh( script: "oc get pvc -n ${NAMESPACE}  -l app.kubernetes.io/name=redis-sentinel --no-headers | wc -l | awk '{print \$1}'",
            returnStdout: true).trim()
    if (pvcCount.equals(valuesReplicas)){
        println "[INFO] PVC in namespace in the same count as replicas"
    } else {
        sh "oc get pvc -n ${NAMESPACE} -l app.kubernetes.io/name=redis-sentinel --no-headers -o name | awk '(NR>${valuesReplicas})' | xargs oc delete -n ${NAMESPACE}"
    }
}

return this;