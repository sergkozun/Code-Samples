package pc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pc.aspect.ExceptionAdvice;
import pc.dao.ServicePointDAO;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

@Service
public class ServicePointsInitializeService implements Runnable {

    private final CyclicBarrier cb = new CyclicBarrier(2, new Action());
    private static final Logger log = LoggerFactory.getLogger(ServicePointsInitializeService.class);

    @Autowired
    private ServicePointDAO servicePointDAO;

    private static Iterator<Map.Entry<Integer, Integer>> clientsUsersIterator;

    private void startNextTwoInitializers() {
        if (clientsUsersIterator.hasNext())
            new ServicePointsInitializer(clientsUsersIterator.next());
        if (clientsUsersIterator.hasNext())
            new ServicePointsInitializer(clientsUsersIterator.next());
    }

    @Override
    public void run() {
        log.debug("Scheduled servicePoints initialization started!");
        clientsUsersIterator = ServicePointDAO.getClientsUsers().entrySet().iterator();
        startNextTwoInitializers();
    }

    public class ServicePointsInitializer implements Runnable {

        private final Logger log = LoggerFactory.getLogger(ServicePointsInitializer.class);

        private final Integer clientId;
        private final Integer userId;

        public ServicePointsInitializer(Integer clientId, Integer userId) {
            this.clientId = clientId;
            this.userId = userId;
            new Thread(this, "ScheduledServicePointsInitializer for new client in clientsUsers").start();
        }

        public ServicePointsInitializer(Map.Entry<Integer, Integer> clientUser) {
            this.clientId = clientUser.getKey();
            this.userId = clientUser.getValue();
            new Thread(this, "ScheduledServicePointsInitializer").start();
        }

        @Override
        public void run() {
            try {
                log.debug("initialization for client " + clientId + " started!");
                servicePointDAO.servicePointsInitialize(clientId, userId);
                log.debug("initialization for client " + clientId + " ended!");
                cb.await();
            } catch (BrokenBarrierException | InterruptedException ex) {
                new ExceptionAdvice().handleException(ex);
                log.warn(ex.getMessage());
            }
        }
    }

    private class Action implements Runnable {
        public void run() {
            startNextTwoInitializers();
        }
    }
}
