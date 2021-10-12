package org.mattia.boller.footballpredictor;

import org.datavec.api.records.reader.SequenceRecordReader;
import org.datavec.api.records.reader.impl.csv.CSVSequenceRecordReader;
import org.datavec.api.records.reader.impl.transform.TransformProcessSequenceRecordReader;
import org.datavec.api.split.NumberedFileInputSplit;
import org.datavec.api.transform.TransformProcess;
import org.datavec.api.transform.schema.Schema;
import org.deeplearning4j.core.storage.StatsStorage;
import org.deeplearning4j.datasets.datavec.SequenceRecordReaderDataSetIterator;
import org.deeplearning4j.datasets.iterator.impl.ListDataSetIterator;
import org.deeplearning4j.earlystopping.EarlyStoppingConfiguration;
import org.deeplearning4j.earlystopping.EarlyStoppingResult;
import org.deeplearning4j.earlystopping.saver.LocalFileModelSaver;
import org.deeplearning4j.earlystopping.scorecalc.DataSetLossCalculator;
import org.deeplearning4j.earlystopping.termination.MaxTimeIterationTerminationCondition;
import org.deeplearning4j.earlystopping.termination.ScoreImprovementEpochTerminationCondition;
import org.deeplearning4j.earlystopping.trainer.EarlyStoppingTrainer;
import org.deeplearning4j.earlystopping.trainer.IEarlyStoppingTrainer;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.conf.layers.recurrent.LastTimeStep;
import org.deeplearning4j.nn.conf.preprocessor.FeedForwardToRnnPreProcessor;
import org.deeplearning4j.nn.conf.preprocessor.RnnToFeedForwardPreProcessor;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.api.InvocationType;
import org.deeplearning4j.optimize.listeners.EvaluativeListener;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.model.stats.StatsListener;
import org.deeplearning4j.ui.model.storage.InMemoryStatsStorage;
import org.jfree.data.general.Dataset;
import org.nd4j.evaluation.classification.Evaluation;
import org.nd4j.evaluation.classification.ROC;
import org.nd4j.evaluation.regression.RegressionEvaluation;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.SplitTestAndTrain;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.CompositeDataSetPreProcessor;
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerMinMaxScaler;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.learning.config.Nadam;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.lossfunctions.impl.LossMCXENT;
import org.nd4j.linalg.lossfunctions.impl.LossMSE;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class FootballPredictorV4 {

    private static String featuresDir = "C:\\Users\\Mattia Boller\\Desktop\\DataPremierLeagueBalanced\\features"; // path to directory containing feature files
    private static String labelsDir = "C:\\Users\\Mattia Boller\\Desktop\\DataPremierLeagueBalanced\\labelsAVG"; // path to directory containing label files
    private static String playersListDir = "C:\\Users\\Mattia Boller\\Desktop\\DataPremierLeague\\playersList"; // path to directory containing label files
    private static String bestModelPath = "out\\2\\model.bin";
    public static final int NB_TRAIN_EXAMPLES = 8000;
    public static final int NB_TEST_EXAMPLES = 2000;
    public static final int BATCH_SIZE = 32;
    public static final int NB_INPUTS = 20;
    public static final int RANDOM_SEED = 1234;

    //Parameters given by the Hyperparameters Optimization
    public static final double LEARNING_RATE = 0.0005;
    public static final int LSTM_LAYER_SIZE = 200;
    public static final int DENSE_LAYER_SIZE = 150;


    public static void main(String[] args) throws IOException, InterruptedException {

        //Starting the UI Server http://localhost:9000/train/overview
        UIServer uiServer = UIServer.getInstance();
        StatsStorage statsStorage = new InMemoryStatsStorage();
        uiServer.attach(statsStorage);

        //Getting the list of players to apply One-hot encoding to the playerId
        BufferedReader buffer = new BufferedReader(new FileReader(playersListDir + File.separator + "playersList.csv"));
        String line;
        ArrayList<String> playersList = new ArrayList<String>();
        while ((line = buffer.readLine()) != null) {
            playersList.add(" " + line);
        }

        //Building schema for data ETL
        Schema schema = new Schema.Builder()
                .addColumnsDouble("rating")
                .addColumnCategorical("playerId", playersList)  //Define categorical variable
                .addColumnInteger("goalsScoredByTeam")
                .addColumnInteger("goalsTakenByTeam")
                .addColumnInteger("cards")
                .addColumnInteger("fouls")
                .addColumnInteger("sucPass")
                .addColumnInteger("unsucPass")
                .addColumnInteger("sucBallTouches")
                .addColumnInteger("unsucBallTouches")
                .addColumnInteger("ballRecovery")
                .addColumnInteger("sucTackles")
                .addColumnInteger("unsucTackels")
                .addColumnInteger("sucTakeOn")
                .addColumnInteger("unsucTakeOn")
                .addColumnInteger("sucAerials")
                .addColumnInteger("unsucAerials")
                .addColumnInteger("goals")
                .addColumnInteger("missedShots")
                .addColumnInteger("savedShots")
                .addColumnInteger("saves")
                .build();

        //Transform operation on data
        TransformProcess transformProcess = new TransformProcess.Builder(schema)
                //.categoricalToOneHot("playerId")//Applying one-hot encoding
                //.removeColumns("playerId[ 18181]")//Removing one categorical field to avoid dummy variable trap
                .removeColumns("playerId")
                .build();

        //Getting data
        SequenceRecordReader trainFeatures = new CSVSequenceRecordReader(1, ",");  // number of rows to skip + delimiter
        trainFeatures.initialize( new NumberedFileInputSplit(featuresDir + "/%d.csv", 0, NB_TRAIN_EXAMPLES - 1));
        SequenceRecordReader trainLabels = new CSVSequenceRecordReader();
        trainLabels.initialize(new NumberedFileInputSplit(labelsDir+ "/%d.csv", 0, NB_TRAIN_EXAMPLES - 1));

        //Passing transformation process to convert the csv file
        SequenceRecordReader transformTrainFeatures = new TransformProcessSequenceRecordReader(trainFeatures,transformProcess);

        DataSetIterator trainData = new SequenceRecordReaderDataSetIterator(transformTrainFeatures, trainLabels,
                BATCH_SIZE, -1, true, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END);

        SequenceRecordReader testFeature = new CSVSequenceRecordReader(1, ",");  // number of rows to skip + delimiter
        testFeature.initialize( new NumberedFileInputSplit(featuresDir + "/%d.csv", NB_TRAIN_EXAMPLES, NB_TRAIN_EXAMPLES + NB_TEST_EXAMPLES - 1));
        SequenceRecordReader testLabels = new CSVSequenceRecordReader();
        testLabels.initialize(new NumberedFileInputSplit(labelsDir+ "/%d.csv", NB_TRAIN_EXAMPLES, NB_TRAIN_EXAMPLES + NB_TEST_EXAMPLES - 1));

        //Passing transformation process to convert the csv file
        SequenceRecordReader transformTestFeatures = new TransformProcessSequenceRecordReader(testFeature,transformProcess);

        DataSetIterator testData = new SequenceRecordReaderDataSetIterator(transformTestFeatures, testLabels,
                1, -1, true, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END);

        NormalizerMinMaxScaler normalizer = new NormalizerMinMaxScaler(-1, 1);
        normalizer.fitLabel(true);
        normalizer.fit(trainData);
        trainData.setPreProcessor(normalizer);
        testData.setPreProcessor(normalizer);

        //Building the Neural Network
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(RANDOM_SEED)
                .weightInit(WeightInit.XAVIER)
                .updater(new Adam(LEARNING_RATE))
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                .gradientNormalizationThreshold(0.5)
                .list()
                .layer(0, new LSTM.Builder()
                        .dropOut(0.8)
                        .activation(Activation.TANH)
                        .nIn(NB_INPUTS)
                        .nOut(LSTM_LAYER_SIZE)
                        .build())
                .layer(1, new RnnOutputLayer.Builder()
                        .lossFunction(LossFunctions.LossFunction.MSE)
                        .activation(Activation.TANH)
                        .nIn(LSTM_LAYER_SIZE)
                        .nOut(1)
                        .build())
                //.inputPreProcessor(1, new RnnToFeedForwardPreProcessor())
                //.inputPreProcessor(3, new FeedForwardToRnnPreProcessor())
                .build();

        MultiLayerNetwork model = new MultiLayerNetwork(conf);
        model.init();

        //model.setListeners(new EvaluativeListener(testData, 1, InvocationType.EPOCH_END, new RegressionEvaluation()), new StatsListener(statsStorage));

        System.out.println(model.summary());

        EarlyStoppingConfiguration<MultiLayerNetwork> eac = new EarlyStoppingConfiguration.Builder<MultiLayerNetwork>()
                .epochTerminationConditions(new ScoreImprovementEpochTerminationCondition(3, 0.00001))
                .iterationTerminationConditions(new MaxTimeIterationTerminationCondition(1, TimeUnit.HOURS))
                .scoreCalculator(new DataSetLossCalculator(testData, true))
                .evaluateEveryNEpochs(1)
                .modelSaver(new LocalFileModelSaver("C:\\Users\\Mattia Boller\\Desktop\\BestModels\\2"))
                .build();

        IEarlyStoppingTrainer<MultiLayerNetwork> trainer = new EarlyStoppingTrainer(eac, model, trainData);

        System.out.println("Training model....");
        EarlyStoppingResult<MultiLayerNetwork> result = trainer.fit();

        System.out.println("Termination reason: " + result.getTerminationReason());
        System.out.println("Termination details: " + result.getTerminationDetails());
        System.out.println("Total epochs: " + result.getTotalEpochs());
        System.out.println("Best epoch number: " + result.getBestModelEpoch());
        System.out.println("Score at best epoch: " + result.getBestModelScore());

        Map<Integer, Double> epochVsScore = result.getScoreVsEpoch();
        List<Integer> modelsList = new ArrayList<Integer>(epochVsScore.keySet());
        Collections.sort(modelsList);
        System.out.println("Epoch\tScore");
        for (Integer i : modelsList) {
            System.out.println(i + "\t" + epochVsScore.get(i));
        }

        testData.reset();

        System.out.println("Evaluation on Training Data....");
        MultiLayerNetwork bestModel = result.getBestModel();
        RegressionEvaluation eval = bestModel.evaluateRegression(trainData);
        System.out.println(eval.stats());

        System.out.println("Evaluation on Testing Data....");
        bestModel = result.getBestModel();
        eval = bestModel.evaluateRegression(testData);
        System.out.println(eval.stats());

        testData.reset();

        bestModel = result.getBestModel();
        double correctPred=0, totalPred=0;
        Integer[][] matrix = new Integer[2][2];
        matrix[0][0] = matrix[0][1] = matrix[1][0] = matrix[1][1] = 0;
        while(testData.hasNext()){
            DataSet example = testData.next();
            INDArray timeSeriesFeatures = example.getFeatures();
            INDArray timeSeriesLabel = example.getLabels();
            INDArray timeSeriesOutput = bestModel.output(timeSeriesFeatures);
            int timeSeriesLength = (int) timeSeriesLabel.size(2);        //Size of time dimension
            normalizer.revertFeatures(timeSeriesFeatures);
            normalizer.revertLabels(timeSeriesLabel);
            normalizer.revertLabels(timeSeriesOutput);
            INDArray lastTimeStepFeatures = timeSeriesFeatures.get(NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.point(timeSeriesLength-1));
            INDArray lastTimeStepLabel = timeSeriesLabel.get(NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.point(timeSeriesLength-1));
            INDArray lastTimeStepPrediction = timeSeriesOutput.get(NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.point(timeSeriesLength-1));

            int oneThird = timeSeriesLength/3;
            double avg=0;
            for(int i=1; i<=oneThird; i++){
                avg += timeSeriesFeatures.getDouble(0, 0, timeSeriesLength-i);
            }
            avg = avg/oneThird;

            BigDecimal lrating=BigDecimal.valueOf(lastTimeStepFeatures.getDouble(0)).setScale(2, BigDecimal.ROUND_DOWN);
            BigDecimal lbl=BigDecimal.valueOf(lastTimeStepLabel.getDouble(0)).setScale(2, BigDecimal.ROUND_DOWN);
            BigDecimal pred=BigDecimal.valueOf(lastTimeStepPrediction.getDouble(0)).setScale(2, BigDecimal.ROUND_DOWN);
            /*
            boolean improved=false, predictedImproved=false;
            if(lbl.compareTo(lrating)>(-1))
                improved=true;
            if(pred.compareTo(lrating)>(-1))
                predictedImproved=true;
            if(improved==predictedImproved)
                correctPred++;
            totalPred++;
             */
            boolean improved=false, predictedImproved=false;
            if(lbl.compareTo(BigDecimal.valueOf(avg-0.02))>(-1))
                improved=true;
            if(pred.compareTo(BigDecimal.valueOf(avg-0.02))>(-1))
                predictedImproved=true;
            if(improved==predictedImproved)
                correctPred++;
            totalPred++;

            if(!improved && !predictedImproved)
                matrix[0][0]++;
            if(!improved && predictedImproved)
                matrix[0][1]++;
            if(improved && !predictedImproved)
                matrix[1][0]++;
            if(improved && predictedImproved)
                matrix[1][1]++;

            System.out.println("Last Rating = " + avg
                    + " Label = " + lbl
                    + " Prediction = " + pred);
        }

        double accuracy = correctPred/totalPred;
        System.out.println("Accuracy = " + accuracy);
        System.out.println(matrix[0][0] + " " +  matrix[0][1]);
        System.out.println(matrix[1][0] + " " +  matrix[1][1]);

        /*
        System.out.println("Evaluation on Training Data....");
        MultiLayerNetwork bestModel = result.getBestModel();
        RegressionEvaluation eval = bestModel.evaluateRegression(trainData.);
        System.out.println(eval.stats());
        trainData.reset();

        System.out.println("Evaluation on Testing Data....");
        bestModel = result.getBestModel();
        eval = bestModel.evaluateRegression(testData);
        System.out.println(eval.stats());
        testData.reset();
         */



    }
}

